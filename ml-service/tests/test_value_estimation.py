"""Value reference builder, expected-information-gain estimator and its internal endpoint."""

import json
import shutil
import subprocess
import sys

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from ml_service.artifacts import DeployedModel
from ml_service.inference.app import create_app
from ml_service.inference.predictor import Predictor
from ml_service.inference.value import METHOD, ValueEstimator, binary_entropy_bits
from ml_service.training import reference as reference_builder
from ml_service.training.reference import build_reference
from ml_service.training.train import load_split
from ml_service.value_reference import (
    METADATA_FILE,
    VALUES_FILE,
    ReferenceError,
    load_value_reference,
    reference_dir,
)

VALUE = "/internal/v1/acquisition-value"
PREDICT = "/internal/v1/predict"


@pytest.fixture(scope="module")
def artifacts(trained, data_dir, config, tmp_path_factory):
    """A copy of the trained artifacts with a value reference built from the synthetic training split."""
    out = tmp_path_factory.mktemp("value-artifacts")
    shutil.copytree(trained[0], out, dirs_exist_ok=True)
    build_reference(data_dir, out, config)
    return out


@pytest.fixture(scope="module")
def reference(artifacts, config):
    return load_value_reference(artifacts, config)


@pytest.fixture(scope="module")
def estimator(artifacts, config, reference):
    from ml_service.artifacts import load_deployed_model

    return ValueEstimator(Predictor(load_deployed_model(artifacts, config), config), reference)


@pytest.fixture(scope="module")
def client(artifacts, config):
    return TestClient(create_app(artifacts, config))


@pytest.fixture(scope="module")
def record(data_dir):
    return load_split(data_dir, "validation").iloc[0]


def observation(record, config, state):
    features = config.observed_features(state)
    return {f: float(record[f]) for f in features}, list(features)


def value_request(record, config, state=(), candidates=None, **extra):
    observed, mask = observation(record, config, state)
    candidates = candidates if candidates is not None else [p for p in config.provider_ids if p not in state]
    return {"observedFeatures": observed, "observedMask": mask, "candidateProviders": candidates, **extra}


# --- Reference artifact ---


def test_reference_holds_only_the_model_features_of_the_training_split(artifacts, data_dir, config):
    metadata = json.loads((reference_dir(artifacts) / METADATA_FILE).read_text())
    train = load_split(data_dir, "train")
    assert metadata["split"] == "train"
    assert metadata["features"] == list(config.model_features)
    assert metadata["rowCount"] == len(train)
    forbidden = {config.record_id_column, config.target_column, *config.excluded_columns}
    assert not forbidden & set(metadata["features"])
    with np.load(reference_dir(artifacts) / VALUES_FILE) as data:
        assert data.files == ["values"]
        np.testing.assert_array_equal(data["values"], train[list(config.model_features)].to_numpy(dtype="float64"))


def test_reference_contains_no_validation_or_test_record(reference, data_dir, config):
    reference_rows = {tuple(row) for row in reference.values}
    train_rows = {tuple(r) for r in load_split(data_dir, "train")[list(config.model_features)].to_numpy("float64")}
    for split in ("validation", "test"):  # synthetic data, so the test split may be read here
        frame = pd.read_csv(data_dir / "restricted" / f"{split}.csv")
        rows = {tuple(r) for r in frame[list(config.model_features)].to_numpy("float64")}
        # Profiles that also occur in training (duplicates) cannot be told apart; all others must be absent.
        assert rows - train_rows
        assert not (rows - train_rows) & reference_rows


def test_builder_reads_only_the_training_split(data_dir, config, tmp_path, monkeypatch):
    read = []
    original = reference_builder.load_split
    monkeypatch.setattr(reference_builder, "load_split", lambda d, split: read.append(split) or original(d, split))
    build_reference(data_dir, tmp_path, config)
    assert read == ["train"]


def test_reference_version_is_deterministic(data_dir, config, tmp_path, artifacts):
    rebuilt = build_reference(data_dir, tmp_path, config)
    assert rebuilt["referenceVersion"] == load_value_reference(artifacts, config).version
    assert rebuilt["referenceVersion"].startswith(f"ref-{config.version}-")


def copy_reference(artifacts, tmp_path):
    shutil.copytree(reference_dir(artifacts), reference_dir(tmp_path))
    return reference_dir(tmp_path)


def test_loader_rejects_a_reference_of_another_feature_configuration(artifacts, config, tmp_path):
    directory = copy_reference(artifacts, tmp_path)
    metadata = json.loads((directory / METADATA_FILE).read_text())
    metadata["featureConfigVersion"] = "fg-other"
    (directory / METADATA_FILE).write_text(json.dumps(metadata))
    with pytest.raises(ReferenceError, match="fg-other"):
        load_value_reference(tmp_path, config)


def test_loader_rejects_a_reference_with_extra_arrays(artifacts, config, tmp_path):
    directory = copy_reference(artifacts, tmp_path)
    with np.load(directory / VALUES_FILE) as data:
        values = data["values"]
    np.savez_compressed(directory / VALUES_FILE, values=values, labels=np.zeros(len(values)))
    with pytest.raises(ReferenceError, match="only feature values"):
        load_value_reference(tmp_path, config)


def test_missing_reference_loads_as_none(config, tmp_path):
    assert load_value_reference(tmp_path, config) is None


# --- Estimator ---


def test_binary_entropy_in_bits():
    assert binary_entropy_bits(0.5) == pytest.approx(1.0)
    assert binary_entropy_bits(0.0) == pytest.approx(0.0, abs=1e-12)
    assert binary_entropy_bits(0.25) == pytest.approx(-(0.25 * np.log2(0.25) + 0.75 * np.log2(0.75)))


@pytest.mark.parametrize("state_index", range(7))  # the eighth state has nothing left to acquire
def test_every_state_with_candidates_gets_valid_estimates(estimator, config, record, state_index):
    state = config.observation_states()[state_index]
    candidates = [p for p in config.provider_ids if p not in state]
    observed, mask = observation(record, config, state)
    result = estimator.estimate(observed, mask, candidates, 50)
    assert [v.provider_id for v in result.values] == candidates
    assert result.sample_size == 50
    assert 0 <= result.current_probability <= 1
    for v in result.values:
        assert v.expected_information_gain_bits >= 0
        assert np.isfinite(v.expected_information_gain_bits)
        assert 0 <= v.expected_probability <= 1
        assert v.expected_absolute_change >= 0


def test_estimates_are_deterministic(estimator, config, record):
    observed, mask = observation(record, config, ())
    candidates = list(config.provider_ids)
    assert estimator.estimate(observed, mask, candidates, 100) == estimator.estimate(observed, mask, candidates, 100)


def test_current_probability_is_the_model_prediction(estimator, config, record):
    observed, mask = observation(record, config, ())
    expected = estimator.predictor.predict(observed, mask)
    assert estimator.estimate(observed, mask, list(config.provider_ids), 20).current_probability == expected


def test_sample_size_is_capped_by_the_reference(estimator, config, record, reference):
    observed, mask = observation(record, config, ())
    result = estimator.estimate(observed, mask, [config.provider_ids[0]], len(reference.values) + 50)
    assert result.sample_size == len(reference.values)


def test_neighbors_depend_only_on_observed_features(estimator, config, record):
    observed, _ = observation(record, config, ())
    neighbors = estimator.neighbors(observed, 30)
    distances = ((estimator.reference.standardized[:, :len(config.initial_features)]
                  - estimator.reference.standardize(np.array(list(observed.values())),
                                                    list(range(len(config.initial_features))))) ** 2).sum(axis=1)
    assert set(neighbors) == set(np.argsort(distances, kind="stable")[:30])


class GroupModel:
    """Stub model: the probability depends only on PAY_0 once Repayment History is acquired."""

    def predict_proba(self, frame: pd.DataFrame) -> np.ndarray:
        pay0 = frame["PAY_0"].to_numpy()
        p = np.where(np.isnan(pay0), 0.3, 1 / (1 + np.exp(-pay0)))
        return np.column_stack([1 - p, p])


@pytest.fixture
def stub_estimator(config, reference):
    return ValueEstimator(Predictor(DeployedModel("stub", GroupModel(), {}), config), reference)


def test_group_that_cannot_change_the_prediction_has_zero_gain(stub_estimator, config, record):
    observed, mask = observation(record, config, ())
    values = {v.provider_id: v for v in stub_estimator.estimate(observed, mask, list(config.provider_ids), 100).values}
    assert values["BILLING_HISTORY"].expected_information_gain_bits == 0
    assert values["PAYMENT_HISTORY"].expected_information_gain_bits == 0
    assert values["BILLING_HISTORY"].expected_probability == pytest.approx(0.3)


def test_group_that_changes_the_prediction_has_positive_gain(stub_estimator, config, record):
    observed, mask = observation(record, config, ())
    result = stub_estimator.estimate(observed, mask, ["REPAYMENT_HISTORY"], 100)
    neighbors = stub_estimator.neighbors(observed, 100)
    pay0 = stub_estimator.reference.values[neighbors, stub_estimator.reference.features.index("PAY_0")]
    p = 1 / (1 + np.exp(-pay0))
    expected = binary_entropy_bits(p.mean()) - binary_entropy_bits(p).mean()
    assert result.values[0].expected_information_gain_bits == pytest.approx(expected)
    if len(set(pay0)) > 1:
        assert result.values[0].expected_information_gain_bits > 0


# --- Endpoint ---


def test_endpoint_returns_estimates_with_versions(client, artifacts, config, record, estimator):
    response = client.post(VALUE, json=value_request(record, config, sampleSize=40))
    assert response.status_code == 200
    body = response.json()
    assert body["method"] == METHOD
    assert body["sampleSize"] == 40
    assert body["referenceVersion"] == load_value_reference(artifacts, config).version
    assert body["featureConfigVersion"] == config.version
    assert [e["providerId"] for e in body["estimates"]] == list(config.provider_ids)
    observed, mask = observation(record, config, ())
    direct = estimator.estimate(observed, mask, list(config.provider_ids), 40)
    for e, v in zip(body["estimates"], direct.values, strict=True):
        assert e["expectedInformationGainBits"] == pytest.approx(v.expected_information_gain_bits)
        assert set(e) == {"providerId", "expectedInformationGainBits", "expectedProbability", "expectedAbsoluteChange"}


def test_default_sample_size_is_100(client, config, record):
    assert client.post(VALUE, json=value_request(record, config)).json()["sampleSize"] == 100


def test_health_reports_the_reference_version(client, artifacts, config):
    assert client.get("/health").json()["valueReferenceVersion"] == load_value_reference(artifacts, config).version


@pytest.mark.parametrize(
    ("build", "fragment"),
    [
        (lambda r, c: value_request(r, c, ("REPAYMENT_HISTORY",), ["REPAYMENT_HISTORY"]), "already acquired"),
        (lambda r, c: value_request(r, c, candidates=["UNKNOWN"]), "Unknown candidate"),
        (lambda r, c: value_request(r, c, candidates=["BILLING_HISTORY", "BILLING_HISTORY"]), "duplicate"),
        (lambda r, c: value_request(r, c, candidates=[]), "at least one"),
        (lambda r, c: {**value_request(r, c), "observedFeatures": {**value_request(r, c)["observedFeatures"],
                                                                    c.target_column: 1},
                       "observedMask": [*value_request(r, c)["observedMask"], c.target_column]}, "not allowed"),
        (lambda r, c: {**value_request(r, c), "observedFeatures": {**value_request(r, c)["observedFeatures"],
                                                                    "PAY_0": 2},
                       "observedMask": [*value_request(r, c)["observedMask"], "PAY_0"]}, "Partial group"),
    ],
    ids=["acquired-candidate", "unknown-candidate", "duplicate-candidate", "no-candidates", "target", "partial-group"],
)
def test_invalid_observations_and_candidates_are_rejected(client, config, record, build, fragment):
    response = client.post(VALUE, json=build(record, config))
    assert response.status_code == 422
    assert response.json()["error"] == "INVALID_OBSERVATION"
    assert fragment in response.json()["message"]


@pytest.mark.parametrize(
    "extra",
    [{"sampleSize": 0}, {"sampleSize": 1001}, {"sampleSize": "50"}, {"label": 1}, {"recordId": "4"}],
    ids=["sample-size-zero", "sample-size-too-large", "sample-size-string", "extra-label", "extra-record-id"],
)
def test_malformed_requests_are_rejected(client, config, record, extra):
    response = client.post(VALUE, json=value_request(record, config, **extra))
    assert response.status_code == 422
    assert response.json()["error"] == "INVALID_REQUEST"


def test_without_reference_prediction_works_and_estimation_is_unavailable(trained, config, record):
    client = TestClient(create_app(trained[0], config))
    observed, mask = observation(record, config, ())
    assert client.post(PREDICT, json={"observedFeatures": observed, "observedMask": mask}).status_code == 200
    response = client.post(VALUE, json=value_request(record, config))
    assert response.status_code == 503
    assert response.json()["error"] == "VALUE_REFERENCE_UNAVAILABLE"
    assert client.get("/health").json()["valueReferenceVersion"] is None


def test_app_with_value_reference_loads_no_offline_modules(artifacts, config, record):
    forbidden = ("ml_service.data", "ml_service.training", "ml_service.analysis")
    request = json.dumps(value_request(record, config))
    script = (
        "import json, sys; from pathlib import Path; from fastapi.testclient import TestClient; "
        "from ml_service.inference.app import create_app; "
        "client = TestClient(create_app(Path(sys.argv[1]))); "
        f"assert client.post({VALUE!r}, json=json.loads(sys.argv[2])).status_code == 200; "
        f"print(sorted(m for m in sys.modules if m.startswith({forbidden!r})))"
    )
    result = subprocess.run(
        [sys.executable, "-c", script, str(artifacts), request], capture_output=True, text=True, check=True
    )
    assert result.stdout.strip() == "[]"


def test_startup_fails_for_a_reference_of_another_feature_configuration(artifacts, config, tmp_path):
    shutil.copytree(artifacts, tmp_path, dirs_exist_ok=True)
    metadata_file = reference_dir(tmp_path) / METADATA_FILE
    metadata = json.loads(metadata_file.read_text())
    metadata["featureConfigVersion"] = "fg-other"
    metadata_file.write_text(json.dumps(metadata))
    with pytest.raises(ReferenceError):
        create_app(tmp_path, config)
