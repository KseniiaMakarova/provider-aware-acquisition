import shutil

import numpy as np
import pandas as pd
import pytest

from ml_service.config import load_feature_config
from ml_service.data.prepare import SOURCE_FILE, make_split_manifest, write_csv, write_split_files
from ml_service.paths import data_dir as real_data_dir

# Few boosting iterations keep training tests fast; settings otherwise match production.
FAST_HGB = {"max_iter": 30}


@pytest.fixture(scope="session")
def config():
    return load_feature_config()


@pytest.fixture(scope="session")
def data_dir(tmp_path_factory, config):
    """A data directory with the real layout, filled with synthetic records."""
    directory = tmp_path_factory.mktemp("data")
    shutil.copy(real_data_dir() / SOURCE_FILE, directory / SOURCE_FILE)
    records = synthetic_records(config, 3000, seed=1)
    manifest = make_split_manifest(records, config, seed=42, validation_fraction=0.15, test_fraction=0.15)
    write_csv(manifest, directory / "split-manifest.csv")
    write_split_files(records, manifest, config, directory)
    return directory


@pytest.fixture(scope="session")
def trained(data_dir, config, tmp_path_factory):
    """Artifacts directory and report from one training run on the synthetic data."""
    from ml_service.training.train import run

    out = tmp_path_factory.mktemp("artifacts")
    return out, run(data_dir, out, config, hgb_overrides=FAST_HGB)


def synthetic_records(config, n: int, seed: int = 0) -> pd.DataFrame:
    """Records with the real column layout and plausible value ranges.

    The target depends on recent repayment status, so models can learn something.
    """
    rng = np.random.default_rng(seed)
    frame = pd.DataFrame({config.record_id_column: np.arange(1, n + 1)})
    for column in (*config.model_features, *config.excluded_columns):
        if column.startswith("PAY_") and not column.startswith("PAY_AMT"):
            frame[column] = rng.integers(-2, 9, n)
        elif column == "LIMIT_BAL":
            frame[column] = rng.integers(1, 51, n) * 10_000
        else:
            frame[column] = rng.integers(0, 200_000, n)
    logit = -1.6 + 0.6 * frame["PAY_0"] + 0.3 * frame["PAY_2"] + rng.normal(0, 1, n)
    frame[config.target_column] = (rng.random(n) < 1 / (1 + np.exp(-logit))).astype("int64")
    return frame


@pytest.fixture
def records(config):
    return synthetic_records(config, 2000)
