"""Model input encoding for partially observed records.

A record is observed in one of the legal acquisition states: the initial features plus
some set of whole provider groups. The model input has one column per model feature,
where features of unacquired groups are NaN, plus one 0/1 indicator per provider group.
Missing values come only from the acquisition state, never from the data, so an
observed zero stays a zero.

Shared by offline training and online inference; it must not import data or training code.
"""

from __future__ import annotations

from collections.abc import Iterable

import numpy as np
import pandas as pd

from ml_service.config import FeatureConfig

INDICATOR_PREFIX = "acquired_"


def indicator_columns(config: FeatureConfig) -> tuple[str, ...]:
    return tuple(INDICATOR_PREFIX + pid for pid in config.provider_ids)


def input_columns(config: FeatureConfig) -> tuple[str, ...]:
    """Column order of the mask-aware model input."""
    return config.model_features + indicator_columns(config)


def state_name(config: FeatureConfig, state: Iterable[str]) -> str:
    """Readable, order-stable label, e.g. 'initial+REPAYMENT_HISTORY'."""
    acquired = set(state)
    return "+".join(["initial", *(pid for pid in config.provider_ids if pid in acquired)])


def encode_state(records: pd.DataFrame, config: FeatureConfig, state: Iterable[str]) -> pd.DataFrame:
    """Encode fully known records as if only the given providers had been acquired."""
    acquired = frozenset(state)
    unknown = acquired - set(config.provider_ids)
    if unknown:
        raise KeyError(f"Unknown provider(s): {sorted(unknown)}")

    encoded = records.loc[:, list(config.model_features)].astype("float64")
    for p in config.providers:
        is_acquired = p.provider_id in acquired
        if not is_acquired:
            encoded.loc[:, list(p.features)] = np.nan
        encoded[INDICATOR_PREFIX + p.provider_id] = 1.0 if is_acquired else 0.0
    return encoded.loc[:, list(input_columns(config))]


def encode_all_states(records: pd.DataFrame, config: FeatureConfig) -> tuple[pd.DataFrame, np.ndarray]:
    """Stack the records once per legal state; returns the inputs and the state index of each row."""
    frames, state_index = [], []
    for i, state in enumerate(config.observation_states()):
        frames.append(encode_state(records, config, state))
        state_index.append(np.full(len(records), i))
    return pd.concat(frames, ignore_index=True), np.concatenate(state_index)
