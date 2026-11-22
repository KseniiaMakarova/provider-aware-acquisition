"""Exploratory check of the value estimator on the validation split (offline).

For a deterministic sample of validation records and every observation state that still has
candidate providers, the estimator is run exactly as online (observed values only). Afterwards,
offline only, the true group values and labels are used to measure what acquiring each provider
actually changed:

- realised change of the probability ``|p_after − p_now|``;
- realised reduction of log loss against the true label, ``ll(p_now) − ll(p_after)``.

All entropies and log losses use base-2 logarithms (bits), so EIG and realised log-loss reduction
have the same unit. They are still different quantities: EIG is the expected variation of the
prediction under sampled values; the realised reduction is one outcome for the true label.

The diagnostic ``p̄ − p_now`` compares the current prediction with the mean of the sampled
predictions; large values show where the neighbor sample or the model's coherence across
observation states is weak.

These are exploratory validation results, not the final evaluation. The test split is not read.

Usage:  python -m ml_service.analysis.value_estimator [--records N] [--sample-size K]
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

from ml_service.artifacts import load_deployed_model
from ml_service.config import FeatureConfig, load_feature_config
from ml_service.encoding import encode_state, state_name
from ml_service.inference.predictor import Predictor
from ml_service.inference.value import METHOD, ValueEstimator
from ml_service.paths import artifacts_dir as default_artifacts_dir
from ml_service.paths import data_dir as default_data_dir
from ml_service.training.train import load_split
from ml_service.value_reference import load_value_reference

SPLIT = "validation"
# Price of one provider call in the neutral scenario under the initial policy parameters
# (0.01 bits per cost unit x 1 + 0.005 bits per second x 0.2 s), used only for reporting.
NEUTRAL_PRICE_BITS = 0.011


def log_loss_bits(p: np.ndarray, y: np.ndarray) -> np.ndarray:
    p = np.clip(p, 1e-15, 1 - 1e-15)
    return -(y * np.log2(p) + (1 - y) * np.log2(1 - p))


def spearman(a: pd.Series, b: pd.Series) -> float | None:
    if a.nunique() < 2 or b.nunique() < 2:
        return None
    return float(a.rank().corr(b.rank()))


def sample_records(records: pd.DataFrame, n: int) -> pd.DataFrame:
    """Every k-th record, so the sample is spread over the file and the same on every run."""
    if n <= 0 or n >= len(records):
        return records
    return records.iloc[:: len(records) // n].head(n)


def analyse(data_dir: Path, artifacts: Path, config: FeatureConfig, n_records: int, sample_size: int) -> dict:
    deployed = load_deployed_model(artifacts, config)
    reference = load_value_reference(artifacts, config)
    if reference is None:
        raise SystemExit("No value reference; run python -m ml_service.training.reference first")
    predictor = Predictor(deployed, config)
    estimator = ValueEstimator(predictor, reference)
    records = sample_records(load_split(data_dir, SPLIT), n_records)
    labels = records[config.target_column].to_numpy()

    rows, timings = [], []
    for state in config.observation_states():
        candidates = [p for p in config.provider_ids if p not in state]
        if not candidates:
            continue
        observed_features = config.observed_features(state)
        after = {g: deployed.model.predict_proba(encode_state(records, config, set(state) | {g}))[:, 1] for g in candidates}
        for i, (_, record) in enumerate(records.iterrows()):
            observed = {f: float(record[f]) for f in observed_features}
            start = time.perf_counter()
            result = estimator.estimate(observed, list(observed_features), candidates, sample_size)
            timings.append(time.perf_counter() - start)
            for v in result.values:
                p_now, p_after = result.current_probability, after[v.provider_id][i]
                rows.append({
                    "state": state_name(config, state),
                    "record": i,
                    "provider": v.provider_id,
                    "eig": v.expected_information_gain_bits,
                    "shift": v.expected_probability - p_now,
                    "realisedAbsChange": abs(p_after - p_now),
                    "realisedLogLossReduction": float(
                        log_loss_bits(np.array([p_now]), labels[i:i + 1])[0]
                        - log_loss_bits(np.array([p_after]), labels[i:i + 1])[0]
                    ),
                })
    frame = pd.DataFrame(rows)

    states = {}
    for state, s in frame.groupby("state", sort=False):
        best = s.loc[s.groupby("record")["eig"].idxmax(), "provider"].value_counts()
        providers = {}
        for provider, d in s.groupby("provider", sort=False):
            providers[provider] = {
                "eigMeanBits": float(d["eig"].mean()),
                "eigMedianBits": float(d["eig"].median()),
                "eigP10Bits": float(d["eig"].quantile(0.1)),
                "eigP90Bits": float(d["eig"].quantile(0.9)),
                "shareAboveNeutralPrice": float((d["eig"] > NEUTRAL_PRICE_BITS).mean()),
                "realisedAbsChangeMean": float(d["realisedAbsChange"].mean()),
                "realisedLogLossReductionMeanBits": float(d["realisedLogLossReduction"].mean()),
                "spearmanEigVsRealisedAbsChange": spearman(d["eig"], d["realisedAbsChange"]),
                "spearmanEigVsRealisedLogLossReduction": spearman(d["eig"], d["realisedLogLossReduction"]),
                "shiftMean": float(d["shift"].mean()),
                "shiftMeanAbs": float(d["shift"].abs().mean()),
                "shiftP95Abs": float(d["shift"].abs().quantile(0.95)),
                "shiftMaxAbs": float(d["shift"].abs().max()),
            }
        states[state] = {"highestEigCounts": {k: int(v) for k, v in best.items()}, "providers": providers}

    return {
        "method": METHOD,
        "split": SPLIT,
        "records": len(records),
        "sampleSize": sample_size,
        "modelVersion": deployed.version,
        "referenceVersion": reference.version,
        "units": "bits (base-2 logarithms) for EIG and log loss",
        "neutralPriceBits": NEUTRAL_PRICE_BITS,
        "meanEstimateMs": 1000 * float(np.mean(timings)),
        "states": states,
    }


def format_report(report: dict) -> str:
    lines = [
        f"Value estimator {report['method']} on {report['records']} {report['split']} records, K={report['sampleSize']}, "
        f"model {report['modelVersion']}, reference {report['referenceVersion']}",
        f"Units: {report['units']}. Mean time per estimate: {report['meanEstimateMs']:.1f} ms",
        "",
        f"{'state / provider':40} {'EIG mean':>8} {'median':>7} {'p90':>7} {'>price':>7} "
        f"{'real LL':>8} {'real|dp|':>8} {'rho|dp|':>7} {'rhoLL':>6} {'shift':>7} {'|shift|':>7} {'p95':>6}",
    ]
    for state, s in report["states"].items():
        lines.append(f"{state}   highest EIG: {s['highestEigCounts']}")
        for provider, m in s["providers"].items():
            rho = m["spearmanEigVsRealisedAbsChange"]
            rho_ll = m["spearmanEigVsRealisedLogLossReduction"]
            lines.append(
                f"  {provider:38} {m['eigMeanBits']:8.4f} {m['eigMedianBits']:7.4f} {m['eigP90Bits']:7.4f} "
                f"{m['shareAboveNeutralPrice']:7.2f} {m['realisedLogLossReductionMeanBits']:8.4f} "
                f"{m['realisedAbsChangeMean']:8.4f} {rho if rho is not None else float('nan'):7.2f} "
                f"{rho_ll if rho_ll is not None else float('nan'):6.2f} {m['shiftMean']:7.4f} "
                f"{m['shiftMeanAbs']:7.4f} {m['shiftP95Abs']:6.3f}"
            )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data-dir", type=Path, default=default_data_dir())
    parser.add_argument("--artifacts-dir", type=Path, default=default_artifacts_dir())
    parser.add_argument("--records", type=int, default=500, help="Validation records to use (0 = all)")
    parser.add_argument("--sample-size", type=int, default=100, help="Nearest reference records (K)")
    args = parser.parse_args(argv)

    config = load_feature_config()
    report = analyse(args.data_dir, args.artifacts_dir, config, args.records, args.sample_size)
    out = args.artifacts_dir / "analysis" / f"value-estimator-{report['modelVersion']}-{report['referenceVersion']}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(format_report(report))
    print(f"\nReport: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
