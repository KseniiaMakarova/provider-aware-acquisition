"""Reference sample for estimating the value of provider information.

The reference is a training-derived artifact: the model features of the training records, with
no record IDs, no labels and no excluded columns. Online value estimation uses it as a sample of
plausible feature values; it never sees validation or test records.

Shared by the offline builder (which writes it) and online inference (which only reads it).
It must not import data or training code.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from ml_service.config import FeatureConfig
from ml_service.transforms import signed_log1p

REFERENCE_DIR = "value-reference"
VALUES_FILE = "reference.npz"
METADATA_FILE = "metadata.json"

SIGNED_LOG1P = "signed_log1p"
IDENTITY = "identity"
TRANSFORMS = {SIGNED_LOG1P: signed_log1p, IDENTITY: lambda x: x}


class ReferenceError(RuntimeError):
    """Raised when the reference artifact does not match the feature configuration."""


@dataclass(frozen=True)
class ValueReference:
    version: str
    features: tuple[str, ...]
    values: np.ndarray  # one row per training record, raw feature values in ``features`` order
    standardized: np.ndarray  # transformed and standardized ``values``, used for distances
    transforms: tuple[str, ...]
    mean: np.ndarray
    std: np.ndarray
    metadata: dict

    def standardize(self, values: np.ndarray, columns: list[int]) -> np.ndarray:
        """Transform and standardize values given for the feature positions ``columns``."""
        out = np.empty(len(columns), dtype="float64")
        for i, c in enumerate(columns):
            out[i] = (TRANSFORMS[self.transforms[c]](values[i]) - self.mean[c]) / self.std[c]
        return out


def standardize_matrix(values: np.ndarray, transforms: tuple[str, ...], mean: np.ndarray, std: np.ndarray) -> np.ndarray:
    transformed = np.column_stack([TRANSFORMS[t](values[:, i]) for i, t in enumerate(transforms)])
    return (transformed - mean) / std


def reference_dir(artifacts: Path) -> Path:
    return artifacts / REFERENCE_DIR


def load_value_reference(artifacts: Path, config: FeatureConfig) -> ValueReference | None:
    """Load the reference, or return None if it has not been built.

    Raises ReferenceError if it exists but was built for another feature configuration.
    """
    directory = reference_dir(artifacts)
    if not (directory / METADATA_FILE).exists():
        return None
    metadata = json.loads((directory / METADATA_FILE).read_text(encoding="utf-8"))
    if metadata["featureConfigVersion"] != config.version:
        raise ReferenceError(
            f"Value reference {metadata['referenceVersion']} was built for feature configuration "
            f"{metadata['featureConfigVersion']}, but {config.version} is loaded"
        )
    features = tuple(metadata["features"])
    if features != config.model_features:
        raise ReferenceError("Value reference features do not match the model features of the configuration")
    with np.load(directory / VALUES_FILE, allow_pickle=False) as data:
        if set(data.files) != {"values"}:
            raise ReferenceError(f"Value reference must contain only feature values, found {sorted(data.files)}")
        values = data["values"].astype("float64")
    if values.shape != (metadata["rowCount"], len(features)) or not np.isfinite(values).all():
        raise ReferenceError("Value reference values do not match its metadata")
    transforms = tuple(metadata["transforms"][f] for f in features)
    mean, std = np.array(metadata["mean"]), np.array(metadata["std"])
    return ValueReference(
        version=metadata["referenceVersion"],
        features=features,
        values=values,
        standardized=standardize_matrix(values, transforms, mean, std),
        transforms=transforms,
        mean=mean,
        std=std,
        metadata=metadata,
    )
