"""Turns an observed feature map and its explicit mask into a prediction."""

from __future__ import annotations

from collections.abc import Mapping, Sequence

import numpy as np
import pandas as pd

from ml_service.artifacts import DeployedModel
from ml_service.config import FeatureConfig, InvalidObservationError
from ml_service.encoding import encode_state


class Predictor:
    def __init__(self, deployed: DeployedModel, config: FeatureConfig):
        self.deployed = deployed
        self.config = config

    @property
    def model_version(self) -> str:
        return self.deployed.version

    def encode(self, observed: Mapping[str, float], mask: Sequence[str]) -> pd.DataFrame:
        """Validate the observation and build the single-row model input.

        The mask must list exactly the keys of ``observed`` and form a legal state: all initial
        features plus whole provider groups. Unobserved features become NaN from the mask only.
        """
        if len(set(mask)) != len(mask):
            raise InvalidObservationError("observedMask contains duplicate feature names")
        if set(mask) != set(observed):
            raise InvalidObservationError(
                "observedMask must list exactly the keys of observedFeatures: "
                f"only in mask {sorted(set(mask) - set(observed))}, "
                f"only in features {sorted(set(observed) - set(mask))}"
            )
        acquired = self.config.acquired_providers(mask)
        row = pd.DataFrame([{f: observed.get(f, np.nan) for f in self.config.model_features}])
        return encode_state(row, self.config, acquired)

    def predict(self, observed: Mapping[str, float], mask: Sequence[str]) -> float:
        return float(self.deployed.model.predict_proba(self.encode(observed, mask))[0, 1])
