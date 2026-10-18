"""Layout of saved model artifacts and loading of the deployed model.

Shared by training (which writes artifacts) and inference (which only reads them).
It must not import data or training code.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import joblib

from ml_service.config import FeatureConfig
from ml_service.encoding import input_columns

MODELS_DIR = "models"
DEPLOYED_FILE = "deployed.json"
MODEL_FILE = "model.joblib"
METADATA_FILE = "metadata.json"


class ArtifactError(RuntimeError):
    """Raised when the deployed model is missing or does not match the feature configuration."""


@dataclass(frozen=True)
class DeployedModel:
    version: str
    model: Any
    metadata: dict


def read_metadata(artifacts: Path, version: str) -> dict:
    return json.loads((artifacts / MODELS_DIR / version / METADATA_FILE).read_text(encoding="utf-8"))


def load_deployed_model(artifacts: Path, config: FeatureConfig) -> DeployedModel:
    """Load the model named by ``deployed.json`` and check it was trained for this configuration."""
    pointer_path = artifacts / DEPLOYED_FILE
    if not pointer_path.exists():
        raise ArtifactError(f"No deployed model at {pointer_path}; run python -m ml_service.training.train")
    pointer = json.loads(pointer_path.read_text(encoding="utf-8"))
    model_dir = artifacts / pointer["path"]
    metadata = json.loads((model_dir / METADATA_FILE).read_text(encoding="utf-8"))

    if metadata["featureConfigVersion"] != config.version:
        raise ArtifactError(
            f"Deployed model {pointer['modelVersion']} was trained with feature configuration "
            f"{metadata['featureConfigVersion']}, but {config.version} is loaded"
        )
    if metadata["inputColumns"] != list(input_columns(config)):
        raise ArtifactError("Deployed model input columns do not match the feature configuration")

    return DeployedModel(pointer["modelVersion"], joblib.load(model_dir / MODEL_FILE), metadata)
