"""Shared feature and provider-group definitions.

Loads ``config/feature-groups.json``, the single source of truth that the Java
backend also reads. The file defines which features are known at session start,
which features each provider reveals, and which dataset columns must never be
used as model inputs.
"""

from __future__ import annotations

import json
import os
from collections.abc import Iterable
from dataclasses import dataclass
from itertools import combinations
from pathlib import Path

from ml_service.paths import REPO_ROOT

CONFIG_PATH_ENV = "PAA_FEATURE_CONFIG"
DEFAULT_CONFIG_PATH = REPO_ROOT / "config" / "feature-groups.json"


class FeatureConfigError(ValueError):
    """Raised when the configuration is malformed or breaks a grouping rule."""


class InvalidObservationError(ValueError):
    """Raised when a set of observed features is not a legal acquisition state."""


@dataclass(frozen=True)
class ProviderGroup:
    provider_id: str
    description: str
    features: tuple[str, ...]


@dataclass(frozen=True)
class FeatureConfig:
    version: str
    record_id_column: str
    target_column: str
    initial_features: tuple[str, ...]
    providers: tuple[ProviderGroup, ...]
    excluded_columns: tuple[str, ...]

    def __post_init__(self) -> None:
        _validate(self)

    @property
    def provider_ids(self) -> tuple[str, ...]:
        return tuple(p.provider_id for p in self.providers)

    @property
    def model_features(self) -> tuple[str, ...]:
        """All model inputs in canonical order: initial features, then each group in file order."""
        return self.initial_features + tuple(f for p in self.providers for f in p.features)

    def provider(self, provider_id: str) -> ProviderGroup:
        for p in self.providers:
            if p.provider_id == provider_id:
                return p
        raise KeyError(f"Unknown provider: {provider_id}")

    def observation_states(self) -> tuple[frozenset[str], ...]:
        """Every legal set of acquired providers, from none to all (2^n states)."""
        ids = self.provider_ids
        return tuple(
            frozenset(combo) for size in range(len(ids) + 1) for combo in combinations(ids, size)
        )

    def observed_features(self, acquired_providers: Iterable[str]) -> tuple[str, ...]:
        """Features visible after the given providers were acquired, in canonical order."""
        acquired = set(acquired_providers)
        unknown = acquired - set(self.provider_ids)
        if unknown:
            raise KeyError(f"Unknown provider(s): {sorted(unknown)}")
        visible = set(self.initial_features)
        for p in self.providers:
            if p.provider_id in acquired:
                visible.update(p.features)
        return tuple(f for f in self.model_features if f in visible)

    def acquired_providers(self, observed_features: Iterable[str]) -> frozenset[str]:
        """Map an observed feature set back to its provider state.

        The set is legal only if it contains every initial feature plus whole
        provider groups. Partial groups, unknown names, excluded columns and the
        target are rejected.
        """
        observed = set(observed_features)
        unknown = observed - set(self.model_features)
        if unknown:
            raise InvalidObservationError(f"Features not allowed as model inputs: {sorted(unknown)}")
        missing_initial = set(self.initial_features) - observed
        if missing_initial:
            raise InvalidObservationError(f"Initial features missing: {sorted(missing_initial)}")
        acquired = set()
        for p in self.providers:
            present = observed & set(p.features)
            if present and present != set(p.features):
                raise InvalidObservationError(
                    f"Partial group for {p.provider_id}: missing {sorted(set(p.features) - present)}"
                )
            if present:
                acquired.add(p.provider_id)
        return frozenset(acquired)


def load_feature_config(path: Path | str | None = None) -> FeatureConfig:
    """Load the configuration from ``path``, the ``PAA_FEATURE_CONFIG`` variable, or the repo default."""
    resolved = Path(path or os.environ.get(CONFIG_PATH_ENV) or DEFAULT_CONFIG_PATH)
    try:
        raw = json.loads(resolved.read_text(encoding="utf-8"))
        return FeatureConfig(
            version=raw["configVersion"],
            record_id_column=raw["dataset"]["recordIdColumn"],
            target_column=raw["dataset"]["targetColumn"],
            initial_features=tuple(raw["initialFeatures"]),
            providers=tuple(
                ProviderGroup(p["id"], p.get("description", ""), tuple(p["features"]))
                for p in raw["providers"]
            ),
            excluded_columns=tuple(raw.get("excludedColumns", [])),
        )
    except (KeyError, TypeError, json.JSONDecodeError) as e:
        raise FeatureConfigError(f"Malformed feature configuration {resolved}: {e!r}") from e


def _validate(config: FeatureConfig) -> None:
    if not config.version:
        raise FeatureConfigError("configVersion must not be empty")
    if not config.initial_features:
        raise FeatureConfigError("initialFeatures must not be empty")
    if not config.providers:
        raise FeatureConfigError("At least one provider is required")

    ids = config.provider_ids
    if len(set(ids)) != len(ids):
        raise FeatureConfigError(f"Duplicate provider ids: {ids}")
    for p in config.providers:
        if not p.provider_id or not p.features:
            raise FeatureConfigError(f"Provider needs an id and at least one feature: {p}")

    features = config.model_features
    duplicates = sorted({f for f in features if features.count(f) > 1})
    if duplicates:
        raise FeatureConfigError(f"Features assigned more than once: {duplicates}")

    forbidden = {config.record_id_column, config.target_column, *config.excluded_columns}
    leaked = sorted(forbidden & set(features))
    if leaked:
        raise FeatureConfigError(f"ID, target or excluded columns used as features: {leaked}")
