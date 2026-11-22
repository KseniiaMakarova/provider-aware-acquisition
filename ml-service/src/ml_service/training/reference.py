"""Build the reference sample for value estimation from the training split.

Only the training split is read. The artifact keeps the model features only: no record IDs,
no labels and no excluded (demographic) columns. The deployed model is not changed.

Usage:  python -m ml_service.training.reference [--data-dir DIR] [--artifacts-dir DIR]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import shutil
import sys
from datetime import UTC, datetime
from pathlib import Path

import numpy as np

from ml_service.config import FeatureConfig, feature_config_path, load_feature_config
from ml_service.data.prepare import SOURCE_FILE, DatasetSource, sha256_of
from ml_service.paths import artifacts_dir as default_artifacts_dir
from ml_service.paths import data_dir as default_data_dir
from ml_service.training.candidates import AMOUNT_PREFIXES
from ml_service.training.train import load_split
from ml_service.value_reference import (
    IDENTITY,
    METADATA_FILE,
    SIGNED_LOG1P,
    TRANSFORMS,
    VALUES_FILE,
    reference_dir,
)

REFERENCE_SPLIT = "train"


def feature_transforms(config: FeatureConfig) -> dict[str, str]:
    """Monetary amounts are compared on a signed-log scale, like in model training; statuses as they are."""
    return {f: SIGNED_LOG1P if f.startswith(AMOUNT_PREFIXES) else IDENTITY for f in config.model_features}


def build_reference(data_dir: Path, artifacts: Path, config: FeatureConfig) -> dict:
    """Write the reference artifact and return its metadata."""
    features = list(config.model_features)
    values = load_split(data_dir, REFERENCE_SPLIT).loc[:, features].to_numpy(dtype="float64")
    transforms = feature_transforms(config)
    transformed = np.column_stack([TRANSFORMS[transforms[f]](values[:, i]) for i, f in enumerate(features)])
    mean = transformed.mean(axis=0)
    std = transformed.std(axis=0)
    std[std == 0] = 1.0

    source = DatasetSource.load(data_dir / SOURCE_FILE)
    provenance = {
        "featureConfigVersion": config.version,
        "featureConfigSha256": sha256_of(feature_config_path()),
        "datasetArchiveSha256": source.sha256,
        "splitManifestSha256": sha256_of(data_dir / source.manifest_name),
        "split": REFERENCE_SPLIT,
        "rowCount": len(values),
        "features": features,
        "transforms": transforms,
        "valuesSha256": hashlib.sha256(np.ascontiguousarray(values).tobytes()).hexdigest(),
    }
    fingerprint = hashlib.sha256(json.dumps(provenance, sort_keys=True).encode()).hexdigest()
    metadata = {
        "referenceVersion": f"ref-{config.version}-{fingerprint[:8]}",
        **provenance,
        "mean": mean.tolist(),
        "std": std.tolist(),
        "createdAt": datetime.now(UTC).isoformat(timespec="seconds"),
        "libraries": {"python": platform.python_version(), "numpy": np.__version__},
    }

    out = reference_dir(artifacts)
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    np.savez_compressed(out / VALUES_FILE, values=values)
    (out / METADATA_FILE).write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    return metadata


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data-dir", type=Path, default=default_data_dir())
    parser.add_argument("--artifacts-dir", type=Path, default=default_artifacts_dir())
    args = parser.parse_args(argv)
    metadata = build_reference(args.data_dir, args.artifacts_dir, load_feature_config())
    print(
        f"Value reference {metadata['referenceVersion']}: {metadata['rowCount']} {metadata['split']} records, "
        f"{len(metadata['features'])} features -> {reference_dir(args.artifacts_dir)}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
