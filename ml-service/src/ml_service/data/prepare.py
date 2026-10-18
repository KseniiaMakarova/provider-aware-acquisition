"""Prepare the UCI credit-default dataset for offline training and provider simulation.

Pipeline: download the archive if missing -> verify its SHA-256 -> read the .xls ->
validate the schema -> split (or reuse the committed split manifest) -> write split files.

Output layout under the data directory:

    features/<split>.csv     ID + model features, no target, validation and test only.
                             The backend reads exactly one of these files.
    restricted/<split>.csv   ID + model features + target, all splits.
                             Used only by offline training and evaluation.

Usage:  python -m ml_service.data.prepare [--data-dir DIR] [--resplit]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

import pandas as pd
from sklearn.model_selection import train_test_split

from ml_service.config import FeatureConfig, load_feature_config
from ml_service.paths import data_dir as default_data_dir

SPLITS = ("train", "validation", "test")
SERVED_SPLITS = ("validation", "test")

FEATURES_DIR = "features"
RESTRICTED_DIR = "restricted"
RAW_DIR = "raw"
SOURCE_FILE = "dataset-source.json"


class DataValidationError(ValueError):
    """Raised when the dataset, archive or split manifest is not as expected."""


@dataclass(frozen=True)
class DatasetSource:
    url: str
    archive_name: str
    sha256: str
    member_name: str
    header_row: int
    manifest_name: str
    seed: int
    validation_fraction: float
    test_fraction: float

    @classmethod
    def load(cls, path: Path) -> DatasetSource:
        raw = json.loads(path.read_text(encoding="utf-8"))
        split = raw["split"]
        return cls(
            url=raw["url"],
            archive_name=raw["archive"]["fileName"],
            sha256=raw["archive"]["sha256"],
            member_name=raw["member"]["fileName"],
            header_row=raw["member"]["headerRow"],
            manifest_name=split["manifest"],
            seed=split["seed"],
            validation_fraction=split["fractions"]["validation"],
            test_fraction=split["fractions"]["test"],
        )


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ensure_archive(source: DatasetSource, raw_dir: Path) -> Path:
    """Download the archive if it is missing, then verify its checksum."""
    archive = raw_dir / source.archive_name
    if not archive.exists():
        raw_dir.mkdir(parents=True, exist_ok=True)
        print(f"Downloading {source.url}")
        urllib.request.urlretrieve(source.url, archive)
    actual = sha256_of(archive)
    if actual != source.sha256:
        raise DataValidationError(
            f"Checksum mismatch for {archive}: expected {source.sha256}, got {actual}. "
            "The source file may have changed; do not continue without reviewing it."
        )
    return archive


def read_archive(archive: Path, source: DatasetSource) -> pd.DataFrame:
    with zipfile.ZipFile(archive) as z, z.open(source.member_name) as member:
        return pd.read_excel(member, header=source.header_row, engine="xlrd")


def validate_records(records: pd.DataFrame, config: FeatureConfig) -> None:
    """Check that the raw table has the columns, types and values the project relies on."""
    required = [
        config.record_id_column,
        *config.model_features,
        *config.excluded_columns,
        config.target_column,
    ]
    missing = [c for c in required if c not in records.columns]
    if missing:
        raise DataValidationError(f"Missing columns: {missing}")
    if records[required].isna().any().any():
        raise DataValidationError("Dataset contains missing values")
    non_numeric = [c for c in required if not pd.api.types.is_integer_dtype(records[c])]
    if non_numeric:
        raise DataValidationError(f"Expected integer columns, got non-integer: {non_numeric}")
    if records[config.record_id_column].duplicated().any():
        raise DataValidationError("Record IDs are not unique")
    labels = set(records[config.target_column].unique())
    if not labels <= {0, 1} or len(labels) != 2:
        raise DataValidationError(f"Target must be binary 0/1, got {sorted(labels)}")


def make_split_manifest(
    records: pd.DataFrame,
    config: FeatureConfig,
    *,
    seed: int,
    validation_fraction: float,
    test_fraction: float,
) -> pd.DataFrame:
    """Stratified train/validation/test assignment, independent of input row order."""
    ordered = records.sort_values(config.record_id_column)
    ids = ordered[config.record_id_column].to_numpy()
    labels = ordered[config.target_column].to_numpy()

    holdout_fraction = validation_fraction + test_fraction
    train_ids, holdout_ids, _, holdout_labels = train_test_split(
        ids, labels, test_size=holdout_fraction, stratify=labels, random_state=seed
    )
    validation_ids, test_ids = train_test_split(
        holdout_ids,
        test_size=test_fraction / holdout_fraction,
        stratify=holdout_labels,
        random_state=seed,
    )

    manifest = pd.concat(
        [
            pd.DataFrame({"ID": train_ids, "split": "train"}),
            pd.DataFrame({"ID": validation_ids, "split": "validation"}),
            pd.DataFrame({"ID": test_ids, "split": "test"}),
        ]
    )
    return manifest.sort_values("ID").reset_index(drop=True)


def validate_manifest(manifest: pd.DataFrame, record_ids: pd.Series) -> None:
    """Every record appears exactly once with a known split name."""
    if list(manifest.columns) != ["ID", "split"]:
        raise DataValidationError(f"Manifest columns must be ['ID', 'split'], got {list(manifest.columns)}")
    unknown_splits = set(manifest["split"]) - set(SPLITS)
    if unknown_splits:
        raise DataValidationError(f"Unknown split names: {sorted(unknown_splits)}")
    if manifest["ID"].duplicated().any():
        raise DataValidationError("A record is assigned to more than one split")
    manifest_ids, dataset_ids = set(manifest["ID"]), set(record_ids)
    if manifest_ids != dataset_ids:
        raise DataValidationError(
            f"Manifest does not match dataset: {len(dataset_ids - manifest_ids)} records unassigned, "
            f"{len(manifest_ids - dataset_ids)} unknown IDs"
        )


def read_manifest(path: Path) -> pd.DataFrame:
    return pd.read_csv(path, dtype={"ID": "int64", "split": "str"})


def write_csv(frame: pd.DataFrame, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    frame.to_csv(path, index=False, lineterminator="\n")


def write_split_files(
    records: pd.DataFrame, manifest: pd.DataFrame, config: FeatureConfig, out_dir: Path
) -> dict[str, Path]:
    """Write the label-free files the backend may read and the restricted offline files."""
    id_col = config.record_id_column
    features = [id_col, *config.model_features]
    restricted = [*features, config.target_column]
    by_split = records.merge(manifest, left_on=id_col, right_on="ID", validate="one_to_one")

    written: dict[str, Path] = {}
    for split in SPLITS:
        rows = by_split[by_split["split"] == split].sort_values(id_col)
        if split in SERVED_SPLITS:
            path = out_dir / FEATURES_DIR / f"{split}.csv"
            write_csv(rows[features], path)
            written[f"{FEATURES_DIR}/{split}"] = path
        path = out_dir / RESTRICTED_DIR / f"{split}.csv"
        write_csv(rows[restricted], path)
        written[f"{RESTRICTED_DIR}/{split}"] = path
    return written


def summarize(records: pd.DataFrame, manifest: pd.DataFrame, config: FeatureConfig) -> dict:
    """Split sizes, class balance and duplicate feature vectors, for the preparation report."""
    joined = records.merge(manifest, left_on=config.record_id_column, right_on="ID")
    splits = {
        split: {
            "records": int((joined["split"] == split).sum()),
            "defaultRate": round(float(joined.loc[joined["split"] == split, config.target_column].mean()), 4),
        }
        for split in SPLITS
    }

    features = list(config.model_features)
    dup_mask = joined.duplicated(subset=features, keep=False)
    groups = joined[dup_mask].groupby(features, sort=False)
    duplicates = {
        "rowsSharingFeatureVector": int(dup_mask.sum()),
        "distinctDuplicatedVectors": int(groups.ngroups),
        "vectorsSpanningSeveralSplits": int((groups["split"].nunique() > 1).sum()),
        "vectorsWithConflictingLabels": int((groups[config.target_column].nunique() > 1).sum()),
    }
    return {
        "records": len(records),
        "defaultRate": round(float(records[config.target_column].mean()), 4),
        "splits": splits,
        "duplicatesOnModelFeatures": duplicates,
    }


def prepare(out_dir: Path, config: FeatureConfig, *, resplit: bool = False) -> dict:
    source = DatasetSource.load(out_dir / SOURCE_FILE)
    archive = ensure_archive(source, out_dir / RAW_DIR)
    records = read_archive(archive, source)
    validate_records(records, config)

    manifest_path = out_dir / source.manifest_name
    if manifest_path.exists() and not resplit:
        manifest = read_manifest(manifest_path)
    else:
        manifest = make_split_manifest(
            records,
            config,
            seed=source.seed,
            validation_fraction=source.validation_fraction,
            test_fraction=source.test_fraction,
        )
        write_csv(manifest, manifest_path)
    validate_manifest(manifest, records[config.record_id_column])

    write_split_files(records, manifest, config, out_dir)
    return summarize(records, manifest, config)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data-dir", type=Path, default=default_data_dir())
    parser.add_argument(
        "--resplit",
        action="store_true",
        help="Recreate the split manifest from the seed instead of reusing the committed one",
    )
    args = parser.parse_args(argv)

    report = prepare(args.data_dir, load_feature_config(), resplit=args.resplit)
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
