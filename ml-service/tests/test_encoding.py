import numpy as np
import pytest

from ml_service.encoding import (
    encode_all_states,
    encode_state,
    indicator_columns,
    input_columns,
    state_name,
)


def test_initial_state_hides_every_optional_group(records, config):
    encoded = encode_state(records, config, [])
    for p in config.providers:
        assert encoded[list(p.features)].isna().all().all(), p.provider_id
        assert (encoded[f"acquired_{p.provider_id}"] == 0).all()
    initial = list(config.initial_features)
    np.testing.assert_array_equal(encoded[initial].to_numpy(), records[initial].to_numpy())


@pytest.mark.parametrize("state_index", range(8))
def test_missing_values_come_only_from_unacquired_groups(records, config, state_index):
    state = config.observation_states()[state_index]
    encoded = encode_state(records, config, state)
    hidden = {f for p in config.providers if p.provider_id not in state for f in p.features}
    assert set(encoded.columns[encoded.isna().any()]) == hidden
    assert not encoded.drop(columns=list(hidden)).isna().any().any()
    for p in config.providers:
        assert (encoded[f"acquired_{p.provider_id}"] == float(p.provider_id in state)).all()
    assert list(encoded.columns) == list(input_columns(config))


def test_observed_zero_stays_zero(records, config):
    records = records.copy()
    records.loc[:, ["BILL_AMT1", "PAY_AMT1", "PAY_2", "PAY_AMT6"]] = 0
    encoded = encode_state(records, config, ["REPAYMENT_HISTORY", "PAYMENT_HISTORY"])
    assert (encoded[["BILL_AMT1", "PAY_AMT1", "PAY_2", "PAY_AMT6"]] == 0).all().all()


def test_target_id_and_demographics_are_never_model_inputs(records, config):
    encoded = encode_state(records, config, config.provider_ids)
    forbidden = {config.target_column, config.record_id_column, *config.excluded_columns}
    assert forbidden.isdisjoint(encoded.columns)


def test_encoding_does_not_modify_the_source_records(records, config):
    before = records.copy()
    encode_state(records, config, [])
    assert records.equals(before)


def test_all_states_are_stacked_once_each(records, config):
    stacked, state_index = encode_all_states(records, config)
    assert len(stacked) == 8 * len(records)
    assert np.bincount(state_index).tolist() == [len(records)] * 8
    indicator_patterns = stacked[list(indicator_columns(config))].drop_duplicates()
    assert len(indicator_patterns) == 8


def test_unknown_provider_is_rejected(records, config):
    with pytest.raises(KeyError):
        encode_state(records, config, ["CREDIT_BUREAU"])


def test_state_names_are_stable(config):
    assert state_name(config, []) == "initial"
    assert state_name(config, ["PAYMENT_HISTORY", "REPAYMENT_HISTORY"]) == (
        "initial+REPAYMENT_HISTORY+PAYMENT_HISTORY"
    )
