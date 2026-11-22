"""Checks on the real deployed model; skipped until data is prepared and training has run."""

import numpy as np
import pytest
from fastapi.testclient import TestClient

from ml_service.artifacts import DEPLOYED_FILE, load_deployed_model
from ml_service.config import load_feature_config
from ml_service.encoding import encode_state
from ml_service.inference.app import create_app
from ml_service.paths import artifacts_dir, data_dir
from ml_service.training.train import load_split
from ml_service.value_reference import load_value_reference

ARTIFACTS = artifacts_dir()

pytestmark = [
    pytest.mark.requires_data,
    pytest.mark.skipif(
        not ((ARTIFACTS / DEPLOYED_FILE).exists() and (data_dir() / "restricted").exists()),
        reason="no deployed model; run python -m ml_service.data.prepare and python -m ml_service.training.train",
    ),
]


@pytest.fixture(scope="module")
def client(config):
    return TestClient(create_app(ARTIFACTS, config))


def test_deployed_model_matches_current_feature_configuration(config):
    deployed = load_deployed_model(ARTIFACTS, config)
    assert deployed.metadata["featureConfigVersion"] == config.version
    assert deployed.metadata["trainingSplits"] == ["train", "validation"]


@pytest.mark.parametrize("state_index", range(8))
def test_service_matches_direct_model_predictions_on_validation_records(client, config, state_index):
    state = config.observation_states()[state_index]
    records = load_split(data_dir(), "validation").head(25)
    expected = load_deployed_model(ARTIFACTS, config).model.predict_proba(encode_state(records, config, state))[:, 1]
    features = config.observed_features(state)
    for (_, record), probability in zip(records.iterrows(), expected):
        body = {"observedFeatures": {f: int(record[f]) for f in features}, "observedMask": list(features)}
        assert client.post("/internal/v1/predict", json=body).json()["probability"] == pytest.approx(probability)


# --- Value reference (after python -m ml_service.training.reference) ---

needs_reference = pytest.mark.skipif(
    load_value_reference(ARTIFACTS, load_feature_config()) is None
    if (ARTIFACTS / DEPLOYED_FILE).exists() else True,
    reason="no value reference; run python -m ml_service.training.reference",
)


@needs_reference
def test_value_reference_is_the_training_split_features(config):
    reference = load_value_reference(ARTIFACTS, config)
    train = load_split(data_dir(), "train")
    assert reference.metadata["split"] == "train"
    assert reference.values.shape == (len(train), len(config.model_features)) == (21000, 19)
    np.testing.assert_array_equal(reference.values, train[list(config.model_features)].to_numpy(dtype="float64"))


@needs_reference
def test_value_estimates_for_a_validation_record(client, config):
    record = load_split(data_dir(), "validation").head(1).iloc[0]
    features = config.initial_features
    body = {
        "observedFeatures": {f: int(record[f]) for f in features},
        "observedMask": list(features),
        "candidateProviders": list(config.provider_ids),
    }
    first = client.post("/internal/v1/acquisition-value", json=body).json()
    assert first == client.post("/internal/v1/acquisition-value", json=body).json()
    assert [e["providerId"] for e in first["estimates"]] == list(config.provider_ids)
    assert all(e["expectedInformationGainBits"] >= 0 for e in first["estimates"])
