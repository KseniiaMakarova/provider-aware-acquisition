import json

import pytest

from ml_service.config import (
    CONFIG_PATH_ENV,
    DEFAULT_CONFIG_PATH,
    FeatureConfigError,
    InvalidObservationError,
    load_feature_config,
)
from ml_service.paths import REPO_ROOT

# The approved primary grouping (fg-2). PAY_0 moved from the initial features into
# REPAYMENT_HISTORY; see docs/decisions/001-feature-grouping-fg-2.md.
APPROVED_VERSION = "fg-2"
APPROVED_INITIAL = ["LIMIT_BAL", "BILL_AMT1", "PAY_AMT1"]
APPROVED_GROUPS = {
    "REPAYMENT_HISTORY": ["PAY_0", "PAY_2", "PAY_3", "PAY_4", "PAY_5", "PAY_6"],
    "BILLING_HISTORY": ["BILL_AMT2", "BILL_AMT3", "BILL_AMT4", "BILL_AMT5", "BILL_AMT6"],
    "PAYMENT_HISTORY": ["PAY_AMT2", "PAY_AMT3", "PAY_AMT4", "PAY_AMT5", "PAY_AMT6"],
}
DEMOGRAPHICS = {"SEX", "EDUCATION", "MARRIAGE", "AGE"}
ARCHIVED_FG1 = REPO_ROOT / "config" / "archive" / "feature-groups-fg-1.json"


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


# --- The shared file matches the approved primary configuration ---


def test_shared_file_is_the_approved_grouping(config):
    assert config.version == APPROVED_VERSION
    assert list(config.initial_features) == APPROVED_INITIAL
    assert {p.provider_id: list(p.features) for p in config.providers} == APPROVED_GROUPS


def test_primary_configuration_has_19_features_and_3_optional_groups(config):
    assert len(config.model_features) == 19
    assert len(config.providers) == 3


def test_demographics_id_and_target_are_not_model_inputs(config):
    assert set(config.excluded_columns) == DEMOGRAPHICS
    forbidden = DEMOGRAPHICS | {config.record_id_column, config.target_column}
    assert forbidden.isdisjoint(config.model_features)


def test_archived_fg1_grouping_still_loads_with_the_same_features(config):
    fg1 = load_feature_config(ARCHIVED_FG1)
    assert fg1.version == "fg-1"
    assert "PAY_0" in fg1.initial_features
    assert set(fg1.model_features) == set(config.model_features)
    assert fg1.provider_ids == config.provider_ids


# --- Observation states (R5) ---


def test_there_are_two_to_the_number_of_providers_states(config):
    states = config.observation_states()
    assert len(states) == 2 ** len(config.providers)
    assert len(set(states)) == len(states)
    assert frozenset() in states
    assert frozenset(config.provider_ids) in states


def test_initial_and_full_states(config):
    assert config.observed_features([]) == config.initial_features
    assert config.observed_features(config.provider_ids) == config.model_features


@pytest.mark.parametrize("state_index", range(8))
def test_every_state_round_trips(config, state_index):
    state = config.observation_states()[state_index]
    features = config.observed_features(state)
    assert config.acquired_providers(features) == state


@pytest.mark.parametrize(
    "build_observed",
    [
        lambda c: [*c.initial_features, c.providers[0].features[-1]],  # partial group
        lambda c: list(c.initial_features[1:]),  # initial feature missing
        lambda c: [*c.initial_features, "AGE"],  # demographic
        lambda c: [*c.initial_features, c.target_column],
        lambda c: [*c.initial_features, c.record_id_column],
        lambda c: [*c.initial_features, "UNKNOWN"],
    ],
    ids=["partial-group", "missing-initial", "demographic", "target", "record-id", "unknown"],
)
def test_illegal_observations_are_rejected(config, build_observed):
    with pytest.raises(InvalidObservationError):
        config.acquired_providers(build_observed(config))


# --- Invalid configuration files are rejected at load time ---


def test_overlapping_groups_are_rejected(tmp_path, raw_config):
    raw_config["providers"][1]["features"].append(raw_config["providers"][0]["features"][0])
    with pytest.raises(FeatureConfigError, match="more than once"):
        load_feature_config(write_config(tmp_path, raw_config))


def test_initial_feature_inside_a_group_is_rejected(tmp_path, raw_config):
    raw_config["providers"][0]["features"].append(raw_config["initialFeatures"][0])
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
