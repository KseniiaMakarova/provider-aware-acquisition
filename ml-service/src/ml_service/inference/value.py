"""Expected information gain of acquiring a provider group in the current observed state.

Method ``knn-eig-v1``, for observed values ``x_o`` and a candidate provider group ``g``:

1. Take the ``K`` reference (training) records nearest to ``x_o``, measured on the observed
   features only (amounts on a signed-log scale, every feature standardized with training
   statistics; equal distances are ordered by reference row, so the result is deterministic).
2. For each neighbor ``k``, fill group ``g`` with the neighbor's values, keep the record's own
   observed values, and predict with the deployed mask-aware model: ``p_k``.
3. ``EIG_g = H(p̄) − mean_k H(p_k)`` in bits, where ``p̄ = mean_k p_k`` and ``H`` is the binary
   entropy with base-2 logarithms. It is the mutual information between the outcome and the
   group's values under the model and the sampled values: never negative, and zero when the
   group cannot change the prediction.

EIG measures how much the hypothetical predictions vary. It is a model-based approximation of
information value, not a guarantee that acquiring the group lowers the prediction error. The
estimate is myopic (one provider at a time) and only as good as the neighbor sample and the
model's probabilities.

Only the request's observed values, the deployed model and the training-derived reference are
used. Hidden values and labels of the current record are not available here.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

import numpy as np
import pandas as pd

from ml_service.config import InvalidObservationError
from ml_service.encoding import encode_state
from ml_service.inference.predictor import Predictor
from ml_service.value_reference import ValueReference

METHOD = "knn-eig-v1"


def binary_entropy_bits(p: np.ndarray) -> np.ndarray:
    p = np.clip(np.asarray(p, dtype="float64"), 1e-15, 1 - 1e-15)
    return -(p * np.log2(p) + (1 - p) * np.log2(1 - p))


@dataclass(frozen=True)
class ProviderValue:
    provider_id: str
    expected_information_gain_bits: float
    expected_probability: float  # p̄: mean of the sampled predictions
    expected_absolute_change: float  # mean |p_k − current probability|


@dataclass(frozen=True)
class ValueEstimate:
    current_probability: float
    sample_size: int
    values: list[ProviderValue]


class ValueEstimator:
    def __init__(self, predictor: Predictor, reference: ValueReference):
        self.predictor = predictor
        self.reference = reference
        self.config = predictor.config

    @property
    def reference_version(self) -> str:
        return self.reference.version

    def neighbors(self, observed: Mapping[str, float], sample_size: int) -> np.ndarray:
        """Row indices of the reference records nearest to the observed values."""
        features = [f for f in self.config.model_features if f in observed]
        columns = [self.reference.features.index(f) for f in features]
        target = self.reference.standardize(np.array([observed[f] for f in features], dtype="float64"), columns)
        distances = ((self.reference.standardized[:, columns] - target) ** 2).sum(axis=1)
        return np.argsort(distances, kind="stable")[: min(sample_size, len(distances))]

    def estimate(
        self, observed: Mapping[str, float], mask: Sequence[str], candidates: Sequence[str], sample_size: int
    ) -> ValueEstimate:
        current_row = self.predictor.encode(observed, mask)  # validates the observation
        acquired = self.config.acquired_providers(mask)
        self._check_candidates(candidates, acquired)
        model = self.predictor.deployed.model
        current = float(model.predict_proba(current_row)[0, 1])

        neighbors = self.neighbors(observed, sample_size)
        k = len(neighbors)
        base = np.array([observed.get(f, np.nan) for f in self.config.model_features], dtype="float64")
        frames = []
        for provider_id in candidates:
            group = [self.reference.features.index(f) for f in self.config.provider(provider_id).features]
            rows = np.tile(base, (k, 1))
            rows[:, group] = self.reference.values[np.ix_(neighbors, group)]
            frame = pd.DataFrame(rows, columns=list(self.config.model_features))
            frames.append(encode_state(frame, self.config, acquired | {provider_id}))
        sampled = model.predict_proba(pd.concat(frames, ignore_index=True))[:, 1].reshape(len(candidates), k)

        values = []
        for provider_id, p in zip(candidates, sampled, strict=True):
            mean_p = float(p.mean())
            gain = float(binary_entropy_bits(mean_p) - binary_entropy_bits(p).mean())
            values.append(
                ProviderValue(
                    provider_id=provider_id,
                    expected_information_gain_bits=max(gain, 0.0),  # >= 0 mathematically; drop rounding noise
                    expected_probability=mean_p,
                    expected_absolute_change=float(np.abs(p - current).mean()),
                )
            )
        return ValueEstimate(current_probability=current, sample_size=k, values=values)

    def _check_candidates(self, candidates: Sequence[str], acquired: frozenset[str]) -> None:
        if not candidates:
            raise InvalidObservationError("candidateProviders must name at least one provider")
        if len(set(candidates)) != len(candidates):
            raise InvalidObservationError("candidateProviders contains duplicate providers")
        unknown = sorted(set(candidates) - set(self.config.provider_ids))
        if unknown:
            raise InvalidObservationError(f"Unknown candidate providers: {unknown}")
        already = sorted(set(candidates) & acquired)
        if already:
            raise InvalidObservationError(f"Candidate providers are already acquired: {already}")
