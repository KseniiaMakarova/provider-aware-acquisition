import json

import pytest

from ml_service.config import (
    CONFIG_PATH_ENV,
    DEFAULT_CONFIG_PATH,
    FeatureConfigError,
    InvalidObservationError,
    load_feature_config,
)

INITIAL = ["LIMIT_BAL", "PAY_0", "BILL_AMT1", "PAY_AMT1"]
GROUPS = {
    "REPAYMENT_HISTORY": ["PAY_2", "PAY_3", "PAY_4", "PAY_5", "PAY_6"],
    "BILLING_HISTORY": ["BILL_AMT2", "BILL_AMT3", "BILL_AMT4", "BILL_AMT5", "BILL_AMT6"],
    "PAYMENT_HISTORY": ["PAY_AMT2", "PAY_AMT3", "PAY_AMT4", "PAY_AMT5", "PAY_AMT6"],
}
DEMOGRAPHICS = {"SEX", "EDUCATION", "MARRIAGE", "AGE"}


@pytest.fixture(scope="module")
def config():
    return load_feature_config()


@pytest.fixture
def raw_config():
    return json.loads(DEFAULT_CONFIG_PATH.read_text(encoding="utf-8"))


def write_config(tmp_path, raw):
    path = tmp_path / "feature-groups.json"
    path.write_text(json.dumps(raw), encoding="utf-8")
    return path


# --- The shared file matches the primary experimental configuration ---


def test_primary_configuration_has_19_features(config):
    assert config.version
    assert list(config.initial_features) == INITIAL
    assert {p.provider_id: list(p.features) for p in config.providers} == GROUPS
    assert len(config.model_features) == 19


def test_demographics_id_and_target_are_not_model_inputs(config):
    assert set(config.excluded_columns) == DEMOGRAPHICS
    forbidden = DEMOGRAPHICS | {config.record_id_column, config.target_column}
    assert forbidden.isdisjoint(config.model_features)


# --- Observation states (R5) ---


def test_there_are_eight_distinct_observation_states(config):
    states = config.observation_states()
    assert len(states) == 8
    assert len(set(states)) == 8
    assert frozenset() in states
    assert frozenset(GROUPS) in states


def test_initial_and_full_states(config):
    assert list(config.observed_features([])) == INITIAL
    assert config.observed_features(GROUPS) == config.model_features


@pytest.mark.parametrize("state_index", range(8))
def test_every_state_round_trips(config, state_index):
    state = config.observation_states()[state_index]
    features = config.observed_features(state)
    assert config.acquired_providers(features) == state


@pytest.mark.parametrize(
    "observed",
    [
        INITIAL + ["PAY_2"],  # partial repayment group
        INITIAL[1:],  # initial feature missing
        INITIAL + ["AGE"],  # demographic
        INITIAL + ["default payment next month"],  # target
        INITIAL + ["ID"],  # record identifier
        INITIAL + ["UNKNOWN"],
    ],
)
def test_illegal_observations_are_rejected(config, observed):
    with pytest.raises(InvalidObservationError):
        config.acquired_providers(observed)


# --- Invalid configuration files are rejected at load time ---


def test_overlapping_groups_are_rejected(tmp_path, raw_config):
    raw_config["providers"][1]["features"].append("PAY_2")
    with pytest.raises(FeatureConfigError, match="more than once"):
        load_feature_config(write_config(tmp_path, raw_config))


def test_initial_feature_inside_a_group_is_rejected(tmp_path, raw_config):
    raw_config["providers"][0]["features"].append("PAY_0")
    with pytest.raises(FeatureConfigError, match="more than once"):
        load_feature_config(write_config(tmp_path, raw_config))


@pytest.mark.parametrize("column", ["AGE", "ID", "default payment next month"])
def test_forbidden_column_as_feature_is_rejected(tmp_path, raw_config, column):
    raw_config["providers"][2]["features"].append(column)
    with pytest.raises(FeatureConfigError, match="used as features"):
        load_feature_config(write_config(tmp_path, raw_config))


def test_duplicate_provider_id_is_rejected(tmp_path, raw_config):
    raw_config["providers"][1]["id"] = raw_config["providers"][0]["id"]
    with pytest.raises(FeatureConfigError, match="Duplicate provider"):
        load_feature_config(write_config(tmp_path, raw_config))


def test_missing_key_is_reported_as_config_error(tmp_path, raw_config):
    del raw_config["initialFeatures"]
    with pytest.raises(FeatureConfigError, match="Malformed"):
        load_feature_config(write_config(tmp_path, raw_config))


def test_environment_variable_overrides_default_path(tmp_path, raw_config, monkeypatch):
    raw_config["configVersion"] = "fg-test"
    monkeypatch.setenv(CONFIG_PATH_ENV, str(write_config(tmp_path, raw_config)))
    assert load_feature_config().version == "fg-test"
