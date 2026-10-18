import pandas as pd
import pytest

from ml_service.data.prepare import (
    FEATURES_DIR,
    RESTRICTED_DIR,
    DataValidationError,
    DatasetSource,
    ensure_archive,
    make_split_manifest,
    validate_manifest,
    validate_records,
    write_split_files,
)

SPLIT_ARGS = {"seed": 42, "validation_fraction": 0.15, "test_fraction": 0.15}


@pytest.fixture
def manifest(records, config):
    return make_split_manifest(records, config, **SPLIT_ARGS)


# --- Schema validation ---


def test_valid_records_pass(records, config):
    validate_records(records, config)


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (lambda df, c: df.drop(columns=["PAY_3"]), "Missing columns"),
        (lambda df, c: df.drop(columns=[c.target_column]), "Missing columns"),
        (lambda df, c: df.assign(**{c.record_id_column: 1}), "not unique"),
        (lambda df, c: df.assign(**{c.target_column: 2}), "binary"),
        (lambda df, c: df.assign(LIMIT_BAL=df["LIMIT_BAL"].astype("float64").where(df.index > 0)), "missing values"),
        (lambda df, c: df.assign(LIMIT_BAL=df["LIMIT_BAL"].astype("float64") + 0.5), "non-integer"),
    ],
)
def test_invalid_records_are_rejected(records, config, mutate, message):
    with pytest.raises(DataValidationError, match=message):
        validate_records(mutate(records, config), config)


# --- Split manifest ---


def test_split_covers_every_record_exactly_once(records, manifest, config):
    validate_manifest(manifest, records[config.record_id_column])
    assert len(manifest) == len(records)


def test_split_sizes_follow_70_15_15(manifest):
    counts = manifest["split"].value_counts()
    assert counts["train"] == 1400
    assert counts["validation"] == 300
    assert counts["test"] == 300


def test_split_is_stratified_by_target(records, manifest, config):
    joined = records.merge(manifest, on=config.record_id_column)
    overall = records[config.target_column].mean()
    for split, rate in joined.groupby("split")[config.target_column].mean().items():
        assert abs(rate - overall) < 0.01, split


def test_split_is_deterministic_and_independent_of_row_order(records, manifest, config):
    shuffled = records.sample(frac=1, random_state=7)
    pd.testing.assert_frame_equal(make_split_manifest(shuffled, config, **SPLIT_ARGS), manifest)


def test_different_seed_gives_different_split(records, manifest, config):
    other = make_split_manifest(records, config, **{**SPLIT_ARGS, "seed": 43})
    assert not other.equals(manifest)


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (lambda m: m.iloc[1:], "unassigned"),
        (lambda m: pd.concat([m, m.iloc[[0]]]), "more than one split"),
        (lambda m: m.assign(split=m["split"].replace("test", "holdout")), "Unknown split"),
        (lambda m: m.assign(ID=m["ID"] + 1), "unknown IDs"),
    ],
)
def test_inconsistent_manifest_is_rejected(records, manifest, config, mutate, message):
    with pytest.raises(DataValidationError, match=message):
        validate_manifest(mutate(manifest), records[config.record_id_column])


# --- Output files: label separation (R4) ---


def test_backend_files_exist_only_for_validation_and_test(tmp_path, records, manifest, config):
    write_split_files(records, manifest, config, tmp_path)
    assert sorted(p.name for p in (tmp_path / FEATURES_DIR).iterdir()) == ["test.csv", "validation.csv"]


@pytest.mark.parametrize("split", ["validation", "test"])
def test_backend_files_contain_no_label_and_no_demographics(tmp_path, records, manifest, config, split):
    write_split_files(records, manifest, config, tmp_path)
    served = pd.read_csv(tmp_path / FEATURES_DIR / f"{split}.csv")
    assert list(served.columns) == [config.record_id_column, *config.model_features]
    expected_ids = set(manifest.loc[manifest["split"] == split, "ID"])
    assert set(served[config.record_id_column]) == expected_ids


def test_backend_file_values_match_source_records(tmp_path, records, manifest, config):
    write_split_files(records, manifest, config, tmp_path)
    served = pd.read_csv(tmp_path / FEATURES_DIR / "validation.csv").set_index(config.record_id_column)
    source = records.set_index(config.record_id_column).loc[served.index, list(config.model_features)]
    pd.testing.assert_frame_equal(served, source, check_names=False)


@pytest.mark.parametrize("split", ["train", "validation", "test"])
def test_restricted_files_add_only_the_target(tmp_path, records, manifest, config, split):
    write_split_files(records, manifest, config, tmp_path)
    restricted = pd.read_csv(tmp_path / RESTRICTED_DIR / f"{split}.csv")
    assert list(restricted.columns) == [config.record_id_column, *config.model_features, config.target_column]
    assert len(restricted) == (manifest["split"] == split).sum()


# --- Archive integrity ---


def test_checksum_mismatch_stops_the_pipeline(tmp_path):
    (tmp_path / "archive.zip").write_bytes(b"not the real dataset")
    source = DatasetSource(
        url="unused",
        archive_name="archive.zip",
        sha256="0" * 64,
        member_name="unused",
        header_row=1,
        manifest_name="split-manifest.csv",
        seed=42,
        validation_fraction=0.15,
        test_fraction=0.15,
    )
    with pytest.raises(DataValidationError, match="Checksum mismatch"):
        ensure_archive(source, tmp_path)
