"""Train the full-information baselines and the mask-aware candidates, then select the deployed model.

Only the train and validation splits are read. Selection uses the mean validation
ROC-AUC across the eight observation states. The selected model is flagged for review,
rather than deployed, if it is clearly worse than the other mask-aware candidate in the
initial or full state, or clearly worse in the full state than the best full-information
baseline.

Usage:  python -m ml_service.training.train [--data-dir DIR] [--artifacts-dir DIR] [--accept-flagged]
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

import joblib
import numpy as np
import pandas as pd
import sklearn

from ml_service.config import FeatureConfig, feature_config_path, load_feature_config
from ml_service.data.prepare import RESTRICTED_DIR, SOURCE_FILE, DatasetSource, sha256_of
from ml_service.encoding import encode_all_states, encode_state, indicator_columns, input_columns, state_name
from ml_service.metrics import METRICS, binary_metrics
from ml_service.paths import artifacts_dir as default_artifacts_dir
from ml_service.paths import data_dir as default_data_dir
from ml_service.training.candidates import FULL_INFORMATION, MASK_AWARE, SEED, Candidate, default_candidates

TRAINING_SPLITS = ("train", "validation")

# A difference in ROC-AUC larger than this is treated as "substantially worse" and needs review.
REVIEW_AUC_MARGIN = 0.01

MODELS_DIR = "models"
REPORT_FILE = "validation-report.json"
DEPLOYED_FILE = "deployed.json"


def load_split(data_dir: Path, split: str) -> pd.DataFrame:
    if split not in TRAINING_SPLITS:
        raise ValueError(f"Split '{split}' is not available to training; the test split is reserved")
    return pd.read_csv(data_dir / RESTRICTED_DIR / f"{split}.csv")


def evaluate_states(model, validation: pd.DataFrame, labels: np.ndarray, config: FeatureConfig) -> dict:
    return {
        state_name(config, state): binary_metrics(
            labels, model.predict_proba(encode_state(validation, config, state))[:, 1]
        )
        for state in config.observation_states()
    }


def mean_metrics(per_state: dict) -> dict:
    return {m: float(np.mean([s[m] for s in per_state.values()])) for m in METRICS}


def train_candidates(
    data_dir: Path, config: FeatureConfig, candidates: list[Candidate]
) -> tuple[dict, dict]:
    train = load_split(data_dir, "train")
    validation = load_split(data_dir, "validation")
    y_train = train[config.target_column].to_numpy()
    y_val = validation[config.target_column].to_numpy()
    features = list(config.model_features)
    full_state = state_name(config, config.provider_ids)

    stacked_inputs, _ = encode_all_states(train, config)
    stacked_labels = np.tile(y_train, len(config.observation_states()))

    results, fitted = {}, {}
    for candidate in candidates:
        if candidate.kind == FULL_INFORMATION:
            model = candidate.build(features).fit(train[features], y_train)
            states = {full_state: binary_metrics(y_val, model.predict_proba(validation[features])[:, 1])}
            entry = {"states": states}
        else:
            model = candidate.build(list(input_columns(config))).fit(stacked_inputs, stacked_labels)
            states = evaluate_states(model, validation, y_val, config)
            entry = {"states": states, "mean": mean_metrics(states)}
        results[candidate.name] = {
            "kind": candidate.kind,
            "family": candidate.family,
            "params": candidate.effective_params(),
            **entry,
        }
        fitted[candidate.name] = model
    return results, fitted


def select_model(results: dict, config: FeatureConfig) -> dict:
    mask_aware = sorted(n for n, r in results.items() if r["kind"] == MASK_AWARE)
    selected = max(mask_aware, key=lambda n: (results[n]["mean"]["rocAuc"], n))
    initial = state_name(config, [])
    full = state_name(config, config.provider_ids)

    flags = []
    for state in (initial, full):
        best_other = max(
            (results[n]["states"][state]["rocAuc"] for n in mask_aware if n != selected), default=None
        )
        own = results[selected]["states"][state]["rocAuc"]
        if best_other is not None and own < best_other - REVIEW_AUC_MARGIN:
            flags.append(f"{selected} ROC-AUC in state '{state}' is {best_other - own:.4f} below another candidate")

    full_information = [n for n, r in results.items() if r["kind"] == FULL_INFORMATION]
    best_full_info = max(results[n]["states"][full]["rocAuc"] for n in full_information)
    gap = best_full_info - results[selected]["states"][full]["rocAuc"]
    if gap > REVIEW_AUC_MARGIN:
        flags.append(f"{selected} full-state ROC-AUC is {gap:.4f} below the best full-information baseline")

    return {
        "selected": selected,
        "criterion": "highest mean validation ROC-AUC across the eight observation states (equal weights)",
        "reviewMargin": REVIEW_AUC_MARGIN,
        "fullStateGapToBestFullInformation": round(gap, 6),
        "flags": flags,
        "reviewRequired": bool(flags),
    }


def provenance(data_dir: Path, config: FeatureConfig) -> dict:
    source = DatasetSource.load(data_dir / SOURCE_FILE)
    return {
        "featureConfigVersion": config.version,
        "featureConfigSha256": sha256_of(feature_config_path()),
        "datasetArchiveSha256": source.sha256,
        "splitManifestSha256": sha256_of(data_dir / source.manifest_name),
        "trainingSplits": list(TRAINING_SPLITS),
        "seed": SEED,
        "libraries": {
            "python": platform.python_version(),
            "scikit-learn": sklearn.__version__,
            "numpy": np.__version__,
            "pandas": pd.__version__,
        },
    }


def model_version(name: str, params: dict, prov: dict) -> str:
    """Deterministic version: the same code inputs, data and settings give the same version."""
    fingerprint = hashlib.sha256(json.dumps({"params": params, **prov}, sort_keys=True).encode()).hexdigest()
    return f"{name}-{prov['featureConfigVersion']}-{fingerprint[:8]}"


def save_artifacts(
    out_dir: Path, results: dict, fitted: dict, selection: dict, prov: dict, config: FeatureConfig,
    *, accept_flagged: bool,
) -> dict:
    created = datetime.now(UTC).isoformat(timespec="seconds")
    versions = {}
    for name, result in results.items():
        if result["kind"] != MASK_AWARE:
            continue
        version = model_version(name, result["params"], prov)
        versions[name] = version
        target = out_dir / MODELS_DIR / version
        if target.exists():
            shutil.rmtree(target)
        target.mkdir(parents=True)
        joblib.dump(fitted[name], target / "model.joblib")
        metadata = {
            "modelVersion": version,
            "candidate": name,
            "family": result["family"],
            "params": result["params"],
            "inputColumns": list(input_columns(config)),
            "modelFeatures": list(config.model_features),
            "indicatorColumns": list(indicator_columns(config)),
            "initialFeatures": list(config.initial_features),
            "providerGroups": {p.provider_id: list(p.features) for p in config.providers},
            "targetColumn": config.target_column,
            "validation": {"states": result["states"], "mean": result["mean"]},
            "createdAt": created,
            **prov,
        }
        (target / "metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")

    report = {"createdAt": created, "provenance": prov, "candidates": results,
              "selection": {**selection, "modelVersion": versions[selection["selected"]]}}
    (out_dir / REPORT_FILE).write_text(json.dumps(report, indent=2), encoding="utf-8")

    deployed = out_dir / DEPLOYED_FILE
    if selection["reviewRequired"] and not accept_flagged:
        deployed.unlink(missing_ok=True)
    else:
        selected_version = versions[selection["selected"]]
        deployed.write_text(
            json.dumps(
                {
                    "modelVersion": selected_version,
                    "path": f"{MODELS_DIR}/{selected_version}",
                    "acceptedWithFlags": selection["reviewRequired"],
                },
                indent=2,
            ),
            encoding="utf-8",
        )
    return report


def format_report(report: dict) -> str:
    candidates = report["candidates"]
    states = list(next(r for r in candidates.values() if r["kind"] == MASK_AWARE)["states"])
    width = max(len(s) for s in states) + 2
    lines = []
    for name, result in candidates.items():
        lines.append(f"\n{name} ({result['kind']})")
        lines.append(f"  {'state':<{width}}{'ROC-AUC':>9}{'PR-AUC':>9}{'Brier':>9}{'LogLoss':>9}")
        rows = list(result["states"].items())
        if "mean" in result:
            rows.append(("MEAN", result["mean"]))
        for state, m in rows:
            lines.append(
                f"  {state:<{width}}{m['rocAuc']:>9.4f}{m['prAuc']:>9.4f}{m['brier']:>9.4f}{m['logLoss']:>9.4f}"
            )
    sel = report["selection"]
    lines.append(f"\nSelected: {sel['selected']} -> {sel['modelVersion']}")
    lines.append(f"Full-state ROC-AUC gap to best full-information baseline: {sel['fullStateGapToBestFullInformation']:.4f}")
    lines.extend(f"REVIEW: {flag}" for flag in sel["flags"])
    return "\n".join(lines)


def run(
    data_dir: Path, out_dir: Path, config: FeatureConfig, *,
    accept_flagged: bool = False, hgb_overrides: dict | None = None,
) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    results, fitted = train_candidates(data_dir, config, default_candidates(hgb_overrides))
    selection = select_model(results, config)
    return save_artifacts(
        out_dir, results, fitted, selection, provenance(data_dir, config), config, accept_flagged=accept_flagged
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data-dir", type=Path, default=default_data_dir())
    parser.add_argument("--artifacts-dir", type=Path, default=default_artifacts_dir())
    parser.add_argument(
        "--accept-flagged",
        action="store_true",
        help="Deploy the selected model even if the selection raised review flags",
    )
    args = parser.parse_args(argv)

    report = run(args.data_dir, args.artifacts_dir, load_feature_config(), accept_flagged=args.accept_flagged)
    print(format_report(report))
    deployed = args.artifacts_dir / DEPLOYED_FILE
    print(f"\nDeployed model pointer: {deployed}" if deployed.exists() else "\nNot deployed: review required")
    return 0


if __name__ == "__main__":
    sys.exit(main())
