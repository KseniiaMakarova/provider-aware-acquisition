import numpy as np
import pandas as pd
import pytest

from ml_service.config import load_feature_config


@pytest.fixture(scope="session")
def config():
    return load_feature_config()


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
