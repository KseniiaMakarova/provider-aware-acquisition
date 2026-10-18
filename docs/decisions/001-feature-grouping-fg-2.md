# 001 — Move `PAY_0` into the Repayment History provider (feature configuration `fg-2`)

**Status:** accepted, October 19, 2026
**Scope:** preliminary refinement of the feature grouping, based on validation results.
The dataset, the prediction target, the set of 19 model features, the data splits and the
excluded demographic attributes are unchanged. The test split was not used.

## Context

The first chapter (Table 6) defined a preliminary grouping:

| Source | Features (`fg-1`) |
|---|---|
| Initial information | `LIMIT_BAL`, `PAY_0`, `BILL_AMT1`, `PAY_AMT1` |
| Repayment History | `PAY_2`–`PAY_6` |
| Billing History | `BILL_AMT2`–`BILL_AMT6` |
| Payment History | `PAY_AMT2`–`PAY_AMT6` |

The methodology allows the grouping to be refined if the initial information turns out to be
unsuitable, provided the change is documented and versioned.

## Evidence from `fg-1`

Mask-aware gradient boosting, validation split (4,500 records):

| State | ROC-AUC |
|---|---|
| initial | 0.7620 |
| initial + all three providers | 0.7713 |

Acquiring every optional provider improved ROC-AUC by only 0.009, which is about the size of the
bootstrap standard error of a validation ROC-AUC (≈ 0.008). Shapley values of the providers were
+0.0087 (Repayment), +0.0011 (Payment) and −0.0006 (Billing) ROC-AUC. In the most confident
fifth of records, acquiring everything did not reduce log loss at all (−0.001).

The reason is `PAY_0`, the most recent repayment status and the strongest single predictor in the
dataset (ROC-AUC 0.699 on its own). Because it was part of the free initial information, there was
almost nothing left for providers to add, so any sensible acquisition policy would stop
immediately and the comparison of strategies would carry little information.

## Decision

Move `PAY_0` from the initial information into Repayment History (`fg-2`):

| Source | Features (`fg-2`) |
|---|---|
| Initial information | `LIMIT_BAL`, `BILL_AMT1`, `PAY_AMT1` |
| Repayment History | `PAY_0`, `PAY_2`–`PAY_6` |
| Billing History | `BILL_AMT2`–`BILL_AMT6` (unchanged) |
| Payment History | `PAY_AMT2`–`PAY_AMT6` (unchanged) |

`PAY_0` is a repayment status, so it belongs to the repayment group conceptually. The initial
information is still financial account data (ER-3). This was the only grouping change considered;
no search over alternative groupings was done.

The `fg-1` configuration is kept in `config/archive/feature-groups-fg-1.json` so its results can be
reproduced.

## Results with `fg-2` (validation split)

Full-information baselines (all 19 features): logistic regression ROC-AUC 0.7338, gradient
boosting 0.7672. These do not depend on the grouping.

Mask-aware gradient boosting, the selected model (`mask-hgb-fg-2-d17dd85b`):

| State | ROC-AUC | PR-AUC | Brier | Log loss |
|---|---|---|---|---|
| initial | 0.6825 | 0.3690 | 0.1597 | 0.4911 |
| + Repayment | 0.7738 | 0.5490 | 0.1365 | 0.4335 |
| + Billing | 0.6991 | 0.3995 | 0.1569 | 0.4845 |
| + Payment | 0.7132 | 0.4030 | 0.1548 | 0.4780 |
| + Repayment + Billing | 0.7726 | 0.5443 | 0.1367 | 0.4344 |
| + Repayment + Payment | 0.7731 | 0.5430 | 0.1370 | 0.4350 |
| + Billing + Payment | 0.7245 | 0.4325 | 0.1519 | 0.4712 |
| all providers | 0.7719 | 0.5372 | 0.1377 | 0.4371 |
| **mean of 8 states** | **0.7388** | **0.4722** | **0.1464** | **0.4581** |

Mask-aware logistic regression had a mean ROC-AUC of 0.7018, so gradient boosting was selected,
with no review flags. In the full state the mask-aware model is 0.0047 ROC-AUC above the
full-information baseline, which is within noise; supporting partial inputs costs no measurable
quality.

## Provider contribution (`fg-2`, validation split)

**Average contribution.** Shapley values over all acquisition orders:

| Provider | ROC-AUC | Log-loss reduction |
|---|---|---|
| Repayment History | +0.0684 | +0.0461 |
| Payment History | +0.0141 | +0.0054 |
| Billing History | +0.0068 | +0.0025 |

**Repayment History dominates.** From every state that does not yet include it, Repayment History
gives the largest improvement (for example +0.0913 ROC-AUC from the initial state).

**Billing and Payment History matter only without Repayment History.** From the initial state they
add +0.0166 and +0.0308 ROC-AUC, and together +0.0420. Once Repayment History is acquired, adding
either has no average benefit (ROC-AUC change −0.0012 and −0.0007). At the record level they still
move some predictions (Payment changes the probability by more than 0.05 for 13% of records), but
the record's own loss improves about as often as it worsens (13.8% vs 13.4%), so this is not a
consistent benefit.

**Per-record differences.** From the initial state, Repayment History gives the largest loss
reduction for 59% of records, Payment History for 24% and Billing History for 17%. This uses the
true labels, so it describes heterogeneity in the data rather than something a policy can achieve.

**Uncertainty.** Records that are uncertain in the initial state gain more from acquisition. Mean
log-loss reduction from acquiring all providers rises from 0.025 in the most confident fifth of
records to 0.066–0.095 in the two least confident fifths, and the mean change in predicted
probability from 0.051 to 0.169.

## Consequences for the acquisition problem

With `fg-2` the providers clearly differ in value, but the problem is not evenly balanced:

- In the default scenario, where all providers are available, a likely strategy is "acquire
  Repayment History, then stop". The per-instance question is then mainly *whether* to acquire it,
  which depends on how uncertain the initial prediction is.
- The choice *between* providers becomes important when Repayment History is unavailable, slow or
  expensive. Then Payment History is the better substitute, followed by Billing History. These are
  the changed-provider scenarios planned in ER-11.

This is reported as a property of the dataset and grouping, not something to optimize away. If
later experiments show that provider selection is nearly trivial under the planned scenarios, that
is a valid finding.

## Limitations

- Validation split only, single split; differences below about ±0.01 ROC-AUC are within noise.
- Fixed model settings, no hyperparameter search.
- Record-level results use labels and are descriptive.

## Reproducing

From `ml-service/`:

```
.venv\Scripts\python -m ml_service.data.prepare
.venv\Scripts\python -m ml_service.training.train
.venv\Scripts\python -m ml_service.analysis.provider_value
```

The `fg-1` results use the archived configuration and an artifacts directory of their own, so the
deployed `fg-2` model is not replaced (PowerShell):

```
$env:PAA_FEATURE_CONFIG = "..\config\archive\feature-groups-fg-1.json"
.venv\Scripts\python -m ml_service.training.train --artifacts-dir artifacts-fg-1
.venv\Scripts\python -m ml_service.analysis.provider_value --artifacts-dir artifacts-fg-1
Remove-Item Env:PAA_FEATURE_CONFIG
```

This reproduces the `fg-1` model version `mask-hgb-fg-1-faec845c`.
