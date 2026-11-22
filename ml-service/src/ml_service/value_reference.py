"""Reference sample for estimating the value of provider information.

The reference is a training-derived artifact: the model features of the training records, with
no record IDs, no labels and no excluded columns. Online value estimation uses it as a sample of
plausible feature values; it never sees validation or test records.

Shared by the offline builder (which writes it) and online inference (which only reads it).
It must not import data or training code.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from ml_service.config import FeatureConfig
from ml_service.transforms import signed_log1p

REFERENCE_DIR = "value-reference"
VALUES_FILE = "reference.npz"
METADATA_FILE = "metadata.json"
REFERENCE_SPLIT = "train"

SIGNED_LOG1P = "signed_log1p"
IDENTITY = "identity"
TRANSFORMS = {SIGNED_LOG1P: signed_log1p, IDENTITY: lambda x: x}

# The reference and the deployed model must come from the same dataset and the same split.
PROVENANCE_KEYS = ("datasetArchiveSha256", "splitManifestSha256")
REQUIRED_KEYS = (
    "referenceVersion",
    "featureConfigVersion",
    "split",
    "rowCount",
    "features",
    "transforms",
    "valuesSha256",
    "mean",
    "std",
    *PROVENANCE_KEYS,
)


class ValueReferenceError(RuntimeError):
    """Raised when the reference artifact is invalid or does not match the configuration or model."""


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


def values_sha256(values: np.ndarray) -> str:
    return hashlib.sha256(np.ascontiguousarray(values, dtype="float64").tobytes()).hexdigest()


def load_value_reference(artifacts: Path, config: FeatureConfig, model_metadata: dict) -> ValueReference | None:
    """Load the reference, or return None if it has not been built.

    ``model_metadata`` is the metadata of the deployed model. The reference must have been built
    from the training split of the same dataset and split as that model; otherwise records that
    the backend serves could be in the reference, together with their hidden values.

    Raises ValueReferenceError if the reference exists but is invalid or does not match.
    """
    directory = reference_dir(artifacts)
    if not (directory / METADATA_FILE).exists():
        return None
    metadata = json.loads((directory / METADATA_FILE).read_text(encoding="utf-8"))
    missing = [key for key in REQUIRED_KEYS if key not in metadata]
    if missing:
        raise ValueReferenceError(f"Value reference metadata is missing {missing}")
    if metadata["featureConfigVersion"] != config.version:
        raise ValueReferenceError(
            f"Value reference {metadata['referenceVersion']} was built for feature configuration "
            f"{metadata['featureConfigVersion']}, but {config.version} is loaded"
        )
    if metadata["split"] != REFERENCE_SPLIT:
        raise ValueReferenceError(f"Value reference must be built from the training split, not {metadata['split']}")
    for key in PROVENANCE_KEYS:
        if metadata[key] != model_metadata.get(key):
            raise ValueReferenceError(
                f"Value reference {metadata['referenceVersion']} and the deployed model come from different data "
                f"({key}); rebuild it with python -m ml_service.training.reference"
            )
    features = tuple(metadata["features"])
    if features != config.model_features:
        raise ValueReferenceError("Value reference features do not match the model features of the configuration")
    with np.load(directory / VALUES_FILE, allow_pickle=False) as data:
        if set(data.files) != {"values"}:
            raise ValueReferenceError(f"Value reference must contain only feature values, found {sorted(data.files)}")
        values = data["values"].astype("float64")
    if values.shape != (metadata["rowCount"], len(features)) or not np.isfinite(values).all():
        raise ValueReferenceError("Value reference values do not match its metadata")
    if values_sha256(values) != metadata["valuesSha256"]:
        raise ValueReferenceError("Value reference values do not match their recorded checksum")
    if set(metadata["transforms"]) != set(features) or not set(metadata["transforms"].values()) <= set(TRANSFORMS):
        raise ValueReferenceError("Value reference transforms are missing or unknown")
    transforms = tuple(metadata["transforms"][f] for f in features)
    mean, std = np.array(metadata["mean"], dtype="float64"), np.array(metadata["std"], dtype="float64")
    if mean.shape != (len(features),) or std.shape != (len(features),):
        raise ValueReferenceError("Value reference scaling parameters do not match its features")
    if not (np.isfinite(mean).all() and np.isfinite(std).all() and (std > 0).all()):
        raise ValueReferenceError("Value reference scaling parameters must be finite, with positive deviations")
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
