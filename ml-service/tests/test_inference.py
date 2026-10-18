import ast
import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from ml_service.artifacts import DEPLOYED_FILE, ArtifactError, load_deployed_model
from ml_service.encoding import encode_state
from ml_service.inference.app import create_app
from ml_service.inference.predictor import Predictor
from ml_service.training.train import load_split

PREDICT = "/internal/v1/predict"


@pytest.fixture(scope="module")
def artifacts(trained):
    return trained[0]


@pytest.fixture(scope="module")
def client(artifacts, config):
    return TestClient(create_app(artifacts, config))


@pytest.fixture(scope="module")
def record(data_dir):
    return load_split(data_dir, "validation").iloc[0]


def request_for(record, config, state):
    features = config.observed_features(state)
    return {"observedFeatures": {f: int(record[f]) for f in features}, "observedMask": list(features)}


def initial_request(record, config):
    return request_for(record, config, [])


# --- Health ---


def test_health_reports_model_and_config_versions(client, artifacts, config):
    body = client.get("/health").json()
    deployed = json.loads((artifacts / DEPLOYED_FILE).read_text())
    assert body == {"status": "UP", "modelVersion": deployed["modelVersion"], "featureConfigVersion": config.version}


# --- Valid predictions ---


@pytest.mark.parametrize("state_index", range(8))
def test_every_legal_state_is_accepted_and_matches_the_model(client, artifacts, config, record, state_index):
    state = config.observation_states()[state_index]
    response = client.post(PREDICT, json=request_for(record, config, state))
    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"probability", "modelVersion", "featureConfigVersion"}

    model = load_deployed_model(artifacts, config).model
    expected = model.predict_proba(encode_state(record.to_frame().T, config, state))[0, 1]
    assert body["probability"] == pytest.approx(expected)
    assert 0 <= body["probability"] <= 1


def test_mask_order_does_not_matter(client, config, record):
    request = request_for(record, config, config.provider_ids)
    shuffled = {**request, "observedMask": list(reversed(request["observedMask"]))}
    assert client.post(PREDICT, json=shuffled).json() == client.post(PREDICT, json=request).json()


def test_observed_zeros_are_values_not_missing(config, artifacts):
    predictor = Predictor(load_deployed_model(artifacts, config), config)
    state = [config.provider_ids[0]]
    features = config.observed_features(state)
    encoded = predictor.encode({f: 0 for f in features}, features)
    assert (encoded[list(features)] == 0).all().all()
    hidden = [f for f in config.model_features if f not in features]
    assert encoded[hidden].isna().all().all()


def test_integer_and_float_values_are_accepted(client, config, record):
    request = initial_request(record, config)
    as_float = {**request, "observedFeatures": {k: float(v) for k, v in request["observedFeatures"].items()}}
    assert client.post(PREDICT, json=as_float).json() == client.post(PREDICT, json=request).json()


# --- Invalid observations (group-level mask rules) ---


def mutate(request, *, add=None, remove=None, mask_add=None, mask_remove=None):
    features = dict(request["observedFeatures"])
    mask = list(request["observedMask"])
    for name, value in (add or {}).items():
        features[name] = value
        mask.append(name)
    for name in remove or []:
        features.pop(name)
        mask.remove(name)
    mask.extend(mask_add or [])
    for name in mask_remove or []:
        mask.remove(name)
    return {"observedFeatures": features, "observedMask": mask}


@pytest.mark.parametrize(
    ("build", "fragment"),
    [
        (lambda r, c: mutate(r, add={c.providers[0].features[0]: 1}), "Partial group"),
        (lambda r, c: mutate(r, remove=[c.initial_features[0]]), "Initial features missing"),
        (lambda r, c: mutate(r, add={c.target_column: 1}), "not allowed"),
        (lambda r, c: mutate(r, add={"AGE": 30}), "not allowed"),
        (lambda r, c: mutate(r, add={c.record_id_column: 5}), "not allowed"),
        (lambda r, c: mutate(r, add={"UNKNOWN": 1}), "not allowed"),
        (lambda r, c: mutate(r, mask_remove=[c.initial_features[0]]), "exactly the keys"),
        (lambda r, c: mutate(r, mask_add=[c.providers[1].features[0]]), "exactly the keys"),
        (lambda r, c: mutate(r, mask_add=[c.initial_features[0]]), "duplicate"),
    ],
    ids=["partial-group", "missing-initial", "target", "demographic", "record-id", "unknown",
         "value-not-in-mask", "mask-without-value", "duplicate-mask"],
)
def test_illegal_observations_are_rejected(client, config, record, build, fragment):
    response = client.post(PREDICT, json=build(initial_request(record, config), config))
    assert response.status_code == 422
    body = response.json()
    assert body["error"] == "INVALID_OBSERVATION"
    assert fragment in body["message"]


# --- Invalid request bodies ---


@pytest.mark.parametrize(
    "bad_value",
    ["1000", True, None, [1], {"v": 1}],
    ids=["numeric-string", "boolean", "null", "list", "object"],
)
def test_non_numeric_values_are_rejected(client, config, record, bad_value):
    request = initial_request(record, config)
    request["observedFeatures"][config.initial_features[0]] = bad_value
    response = client.post(PREDICT, json=request)
    assert response.status_code == 422
    assert response.json()["error"] == "INVALID_REQUEST"


@pytest.mark.parametrize("literal", ["NaN", "Infinity"])
def test_nan_and_infinity_are_rejected(client, config, record, literal):
    request = initial_request(record, config)
    request["observedFeatures"][config.initial_features[0]] = "__VALUE__"
    body = json.dumps(request).replace('"__VALUE__"', literal)
    response = client.post(PREDICT, content=body, headers={"Content-Type": "application/json"})
    assert response.status_code == 422


@pytest.mark.parametrize(
    "build",
    [
        lambda r, c: {**r, "label": 1},
        lambda r, c: {**r, "recordId": "123"},
        lambda r, c: {"observedFeatures": r["observedFeatures"]},
        lambda r, c: {"observedMask": r["observedMask"]},
    ],
    ids=["extra-label-field", "extra-record-id-field", "missing-mask", "missing-features"],
)
def test_malformed_bodies_are_rejected(client, config, record, build):
    response = client.post(PREDICT, json=build(initial_request(record, config), config))
    assert response.status_code == 422
    assert response.json()["error"] == "INVALID_REQUEST"


# --- Startup guards ---


def test_startup_fails_without_a_deployed_model(tmp_path, config):
    with pytest.raises(ArtifactError, match="No deployed model"):
        create_app(tmp_path, config)


def test_startup_fails_for_a_model_of_another_feature_configuration(artifacts, config, tmp_path):
    copy = tmp_path / "artifacts"
    shutil.copytree(artifacts, copy)
    other = config.__class__(**{**config.__dict__, "version": "fg-other"})
    with pytest.raises(ArtifactError, match="trained with feature configuration"):
        create_app(copy, other)


# --- Boundary: online inference is independent of data, training and analysis code ---

FORBIDDEN = ("ml_service.data", "ml_service.training", "ml_service.analysis")
INFERENCE_SOURCES = [
    *(Path(__file__).parents[1] / "src" / "ml_service" / "inference").glob("*.py"),
    Path(__file__).parents[1] / "src" / "ml_service" / "artifacts.py",
    Path(__file__).parents[1] / "src" / "ml_service" / "encoding.py",
]


@pytest.mark.parametrize("source", INFERENCE_SOURCES, ids=lambda p: p.name)
def test_inference_sources_do_not_import_offline_code(source):
    tree = ast.parse(source.read_text(encoding="utf-8"))
    imported = [n.module for n in ast.walk(tree) if isinstance(n, ast.ImportFrom) and n.module]
    imported += [a.name for n in ast.walk(tree) if isinstance(n, ast.Import) for a in n.names]
    assert not [m for m in imported if m.startswith(FORBIDDEN)]


def test_running_app_loads_no_offline_modules(artifacts):
    script = (
        "import sys; from pathlib import Path; from ml_service.inference.app import create_app; "
        "create_app(Path(sys.argv[1])); "
        f"print(sorted(m for m in sys.modules if m.startswith({FORBIDDEN!r})))"
    )
    result = subprocess.run(
        [sys.executable, "-c", script, str(artifacts)], capture_output=True, text=True, check=True
    )
    assert result.stdout.strip() == "[]"
