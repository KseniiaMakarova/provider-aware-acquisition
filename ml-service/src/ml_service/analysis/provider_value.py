"""How much each provider group contributes to predictions on the validation split.

Offline analysis to check that the feature grouping gives a meaningful acquisition
problem. It uses the validation labels, so it describes the data; it is not a policy.

For a saved mask-aware model it reports:
- quality per observation state;
- the marginal effect of adding each provider to each state that lacks it, and Shapley
  values (average marginal effect over all acquisition orders);
- which provider helps most from each state;
- per-record changes: how often a provider moves the prediction or the record's loss;
- whether records that are uncertain in the initial state gain more from acquisition.

Usage:  python -m ml_service.analysis.provider_value [--model-version VERSION]
"""

from __future__ import annotations

import argparse
import json
import sys
from itertools import combinations
from math import factorial
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

from ml_service.config import FeatureConfig, load_feature_config
from ml_service.encoding import encode_state, state_name
from ml_service.metrics import binary_metrics
from ml_service.paths import artifacts_dir as default_artifacts_dir
from ml_service.paths import data_dir as default_data_dir
from ml_service.artifacts import DEPLOYED_FILE, METADATA_FILE, MODEL_FILE, MODELS_DIR
from ml_service.training.train import load_split

ANALYSIS_DIR = "analysis"
CHANGE_THRESHOLDS = (0.05, 0.10)
LOSS_THRESHOLD = 0.05
UNCERTAINTY_BINS = 5


def record_log_loss(y, p) -> np.ndarray:
    y = np.asarray(y, dtype="float64")
    p = np.clip(np.asarray(p, dtype="float64"), 1e-15, 1 - 1e-15)
    return -(y * np.log(p) + (1 - y) * np.log(1 - p))


def binary_entropy(p) -> np.ndarray:
    p = np.clip(np.asarray(p, dtype="float64"), 1e-15, 1 - 1e-15)
    return -(p * np.log2(p) + (1 - p) * np.log2(1 - p))


def predict_all_states(model, records: pd.DataFrame, config: FeatureConfig) -> dict[frozenset, np.ndarray]:
    return {
        state: model.predict_proba(encode_state(records, config, state))[:, 1]
        for state in config.observation_states()
    }


def shapley(values: dict[frozenset, float], providers: tuple[str, ...]) -> dict[str, float]:
    """Exact Shapley value of each provider for a set function over acquired-provider sets."""
    n = len(providers)
    result = {}
    for p in providers:
        others = [q for q in providers if q != p]
        total = 0.0
        for size in range(n):
            weight = factorial(size) * factorial(n - size - 1) / factorial(n)
            for subset in combinations(others, size):
                base = frozenset(subset)
                total += weight * (values[base | {p}] - values[base])
        result[p] = total
    return result


def analyze(model, validation: pd.DataFrame, config: FeatureConfig) -> dict:
    y = validation[config.target_column].to_numpy()
    probs = predict_all_states(model, validation, config)
    losses = {s: record_log_loss(y, p) for s, p in probs.items()}
    metrics = {s: binary_metrics(y, p) for s, p in probs.items()}
    providers = config.provider_ids
    name = lambda s: state_name(config, s)  # noqa: E731

    transitions = []
    for base in config.observation_states():
        for p in providers:
            if p in base:
                continue
            after = base | {p}
            delta_p = probs[after] - probs[base]
            gain = losses[base] - losses[after]
            transitions.append(
                {
                    "provider": p,
                    "from": name(base),
                    "deltaRocAuc": metrics[after]["rocAuc"] - metrics[base]["rocAuc"],
                    "logLossReduction": metrics[base]["logLoss"] - metrics[after]["logLoss"],
                    "brierReduction": metrics[base]["brier"] - metrics[after]["brier"],
                    "meanAbsProbabilityChange": float(np.mean(np.abs(delta_p))),
                    **{
                        f"shareProbabilityChangeOver{int(t * 100):02d}": float(np.mean(np.abs(delta_p) > t))
                        for t in CHANGE_THRESHOLDS
                    },
                    "shareLossImprovedOver05": float(np.mean(gain > LOSS_THRESHOLD)),
                    "shareLossWorsenedOver05": float(np.mean(gain < -LOSS_THRESHOLD)),
                }
            )

    best_from_state = {}
    for base in config.observation_states():
        options = [t for t in transitions if t["from"] == name(base)]
        if options:
            best = max(options, key=lambda t: t["logLossReduction"])
            best_from_state[name(base)] = best["provider"]

    # Which single provider would most reduce each record's loss from the initial state
    # (uses labels; shows heterogeneity, not an achievable policy).
    initial = frozenset()
    per_record_gain = np.column_stack([losses[initial] - losses[frozenset({p})] for p in providers])
    best_single = pd.Series(np.array(providers)[per_record_gain.argmax(axis=1)]).value_counts(normalize=True)

    full = frozenset(providers)
    entropy = binary_entropy(probs[initial])
    bins = pd.qcut(entropy, UNCERTAINTY_BINS, labels=False, duplicates="drop")
    first = providers[0]
    after_first = frozenset({first})
    by_uncertainty = []
    for b in sorted(np.unique(bins)):
        mask = bins == b
        by_uncertainty.append(
            {
                "bin": int(b) + 1,
                "records": int(mask.sum()),
                "initialEntropyRange": [float(entropy[mask].min()), float(entropy[mask].max())],
                "meanLossReductionInitialToFull": float(np.mean((losses[initial] - losses[full])[mask])),
                "meanAbsChangeInitialToFull": float(np.mean(np.abs(probs[full] - probs[initial])[mask])),
                **{
                    f"meanAbsChangeAdding_{p}": float(
                        np.mean(np.abs(probs[frozenset({p})] - probs[initial])[mask])
                    )
                    for p in providers
                },
                f"meanLossReductionAfter_{first}_ToFull": float(
                    np.mean((losses[after_first] - losses[full])[mask])
                ),
            }
        )

    return {
        "records": int(len(validation)),
        "states": {name(s): m for s, m in metrics.items()},
        "transitions": transitions,
        "shapley": {
            "rocAuc": shapley({s: m["rocAuc"] for s, m in metrics.items()}, providers),
            "logLossReduction": shapley({s: -m["logLoss"] for s, m in metrics.items()}, providers),
        },
        "bestProviderFromState": best_from_state,
        "bestSingleProviderPerRecordFromInitial": {k: float(v) for k, v in best_single.items()},
        "byInitialUncertainty": by_uncertainty,
    }


def format_analysis(result: dict, version: str) -> str:
    lines = [f"Provider value analysis for {version} on {result['records']} validation records"]
    lines.append("\nShapley values (average marginal contribution over acquisition orders)")
    for p in result["shapley"]["rocAuc"]:
        lines.append(
            f"  {p:<20} ROC-AUC {result['shapley']['rocAuc'][p]:+.4f}   "
            f"log-loss reduction {result['shapley']['logLossReduction'][p]:+.4f}"
        )
    lines.append("\nMarginal effect of adding a provider")
    lines.append(
        f"  {'provider':<19}{'from state':<44}{'dAUC':>8}{'dLL':>8}{'|dp|':>7}{'>0.05':>7}{'>0.10':>7}{'LL+':>7}{'LL-':>7}"
    )
    for t in result["transitions"]:
        lines.append(
            f"  {t['provider']:<19}{t['from']:<44}{t['deltaRocAuc']:>+8.4f}{t['logLossReduction']:>+8.4f}"
            f"{t['meanAbsProbabilityChange']:>7.3f}{t['shareProbabilityChangeOver05']:>7.1%}"
            f"{t['shareProbabilityChangeOver10']:>7.1%}{t['shareLossImprovedOver05']:>7.1%}"
            f"{t['shareLossWorsenedOver05']:>7.1%}"
        )
    lines.append("  (dLL: log-loss reduction; |dp|: mean absolute probability change; >0.05/>0.10: share of")
    lines.append("   records whose probability moves more than that; LL+/LL-: share whose own loss improves/worsens by >0.05)")
    lines.append("\nBest provider by log-loss reduction from each state")
    for state, p in result["bestProviderFromState"].items():
        lines.append(f"  {state:<50} -> {p}")
    lines.append("\nShare of records for which each single provider reduces their own loss most (from initial)")
    for p, share in result["bestSingleProviderPerRecordFromInitial"].items():
        lines.append(f"  {p:<20} {share:.1%}")
    lines.append("\nBy initial-state uncertainty (entropy quintiles, 1 = most confident)")
    keys = [k for k in result["byInitialUncertainty"][0] if k.startswith(("meanLoss", "meanAbs"))]
    for row in result["byInitialUncertainty"]:
        values = "  ".join(f"{k.replace('mean', '')}={row[k]:.3f}" for k in keys)
        lines.append(f"  Q{row['bin']} ({row['records']} records): {values}")
    return "\n".join(lines)


def run(data_dir: Path, artifacts: Path, config: FeatureConfig, version: str | None = None) -> tuple[str, dict]:
    if version is None:
        version = json.loads((artifacts / DEPLOYED_FILE).read_text(encoding="utf-8"))["modelVersion"]
    model_dir = artifacts / MODELS_DIR / version
    metadata = json.loads((model_dir / METADATA_FILE).read_text(encoding="utf-8"))
    if metadata["featureConfigVersion"] != config.version:
        raise ValueError(
            f"Model {version} was trained with {metadata['featureConfigVersion']}, but the loaded "
            f"feature configuration is {config.version}; set PAA_FEATURE_CONFIG to the matching file"
        )
    result = analyze(joblib.load(model_dir / MODEL_FILE), load_split(data_dir, "validation"), config)
    out = artifacts / ANALYSIS_DIR / f"provider-value-{version}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2), encoding="utf-8")
    return version, result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data-dir", type=Path, default=default_data_dir())
    parser.add_argument("--artifacts-dir", type=Path, default=default_artifacts_dir())
    parser.add_argument("--model-version", help="Defaults to the deployed model")
    args = parser.parse_args(argv)
    version, result = run(args.data_dir, args.artifacts_dir, load_feature_config(), args.model_version)
    print(format_analysis(result, version))
    return 0


if __name__ == "__main__":
    sys.exit(main())
