"""Checks on the real prepared dataset and the committed split manifest.

Manifest-only tests always run, because the manifest is versioned. Tests that need
the downloaded archive or generated files are skipped until the pipeline has run.
"""

import pandas as pd
import pytest

from ml_service.config import load_feature_config
from ml_service.data.prepare import (
    FEATURES_DIR,
    RAW_DIR,
    RESTRICTED_DIR,
    SOURCE_FILE,
    DatasetSource,
    make_split_manifest,
    read_archive,
    read_manifest,
    sha256_of,
)
from ml_service.paths import data_dir

DATA = data_dir()
SOURCE = DatasetSource.load(DATA / SOURCE_FILE)
ARCHIVE = DATA / RAW_DIR / SOURCE.archive_name

requires_data = pytest.mark.skipif(
    not (ARCHIVE.exists() and (DATA / FEATURES_DIR).exists()),
    reason="dataset not prepared; run python -m ml_service.data.prepare",
)


@pytest.fixture(scope="module")
def config():
    return load_feature_config()


@pytest.fixture(scope="module")
def manifest():
    return read_manifest(DATA / SOURCE.manifest_name)


# --- Committed manifest (no download needed) ---


def test_committed_manifest_has_expected_sizes(manifest):
    assert len(manifest) == 30_000
    assert manifest["ID"].is_unique
    assert set(manifest["ID"]) == set(range(1, 30_001))
    assert manifest["split"].value_counts().to_dict() == {"train": 21_000, "validation": 4_500, "test": 4_500}


# --- Real data ---


@pytest.mark.requires_data
@requires_data
def test_archive_matches_recorded_checksum():
    assert sha256_of(ARCHIVE) == SOURCE.sha256


@pytest.mark.requires_data
@requires_data
def test_committed_manifest_is_reproducible_from_seed(manifest, config):
    regenerated = make_split_manifest(
        read_archive(ARCHIVE, SOURCE),
        config,
        seed=SOURCE.seed,
        validation_fraction=SOURCE.validation_fraction,
        test_fraction=SOURCE.test_fraction,
    )
    pd.testing.assert_frame_equal(regenerated, manifest)


@pytest.mark.requires_data
@requires_data
def test_every_split_keeps_the_overall_default_rate(config):
    rates = {
        split: pd.read_csv(DATA / RESTRICTED_DIR / f"{split}.csv")[config.target_column].mean()
        for split in ("train", "validation", "test")
    }
    overall = 6_636 / 30_000
    for split, rate in rates.items():
        assert abs(rate - overall) < 0.001, split


@pytest.mark.requires_data
@requires_data
@pytest.mark.parametrize("split", ["validation", "test"])
def test_backend_files_hold_only_their_split_without_labels(manifest, config, split):
    served = pd.read_csv(DATA / FEATURES_DIR / f"{split}.csv")
    assert list(served.columns) == [config.record_id_column, *config.model_features]
    assert set(served[config.record_id_column]) == set(manifest.loc[manifest["split"] == split, "ID"])


@pytest.mark.requires_data
@requires_data
def test_no_training_records_are_exposed_to_the_backend():
    assert not (DATA / FEATURES_DIR / "train.csv").exists()
