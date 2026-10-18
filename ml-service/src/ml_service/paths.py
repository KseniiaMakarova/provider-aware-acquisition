"""Repository-relative locations, overridable through environment variables."""

from __future__ import annotations

import os
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]

DATA_DIR_ENV = "PAA_DATA_DIR"
ARTIFACTS_DIR_ENV = "PAA_ARTIFACTS_DIR"


def data_dir() -> Path:
    return Path(os.environ.get(DATA_DIR_ENV) or REPO_ROOT / "data")


def artifacts_dir() -> Path:
    return Path(os.environ.get(ARTIFACTS_DIR_ENV) or REPO_ROOT / "ml-service" / "artifacts")
