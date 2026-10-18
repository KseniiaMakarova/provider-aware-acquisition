"""Checks on the real deployed model; skipped until data is prepared and training has run."""

import pytest
from fastapi.testclient import TestClient

from ml_service.artifacts import DEPLOYED_FILE, load_deployed_model
from ml_service.encoding import encode_state
from ml_service.inference.app import create_app
from ml_service.paths import artifacts_dir, data_dir
from ml_service.training.train import load_split

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
