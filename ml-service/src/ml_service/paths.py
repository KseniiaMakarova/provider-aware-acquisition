"""Repository-relative locations, overridable through environment variables."""

from __future__ import annotations

import os
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]

DATA_DIR_ENV = "PAA_DATA_DIR"


def data_dir() -> Path:
    return Path(os.environ.get(DATA_DIR_ENV) or REPO_ROOT / "data")
