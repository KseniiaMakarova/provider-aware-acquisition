# 002 — Expected information gain and the provider-aware decision rule

**Status:** accepted, November 22, 2026 (extended for timeouts the same day)
**Scope:** the initial value-estimation method, adaptive decision rule and stopping criterion of
the MVP. The coefficients are provisional experimental parameters; their calibration, sensitivity
analysis and the comparative evaluation are planned for Phase 4. The test split was not used.

## Context

The adaptive policy must decide, per record and step, whether another provider is worth querying
and which one. The architecture fixes the split of responsibilities: the ML service estimates the
value of each candidate provider from the observed state, and the Java policy decides, taking the
provider's cost, latency, reliability and availability into account. The estimate may use only
the observed values, the deployed model and training-derived information; never hidden values or
the label of the current record.

## Decision: value estimation (ML service)

Method `knn-eig-v1`, for observed values `x_o` and a candidate provider group `g`:

1. Take the `K = 100` training records nearest to `x_o`, measured on the observed features only.
   Monetary amounts are compared on a signed-log scale, every feature is standardized with
   training statistics, and equal distances are ordered by row, so the estimate is deterministic.
2. For each neighbor `k`, fill group `g` with the neighbor's values, keep the record's own observed
   values, and predict with the deployed mask-aware model: `p_k`.
3. `EIG_g = H(p̄) − (1/K) Σ_k H(p_k)`, with `p̄ = (1/K) Σ_k p_k` and the binary entropy
   `H(p) = −p log₂ p − (1 − p) log₂(1 − p)`, in **bits**.

EIG is the mutual information between the outcome and the group's values under the model and the
sampled values. It is never negative and is zero when the group cannot change the prediction. The
neighbors come from a training-derived reference (`artifacts/value-reference/`, built by
`python -m ml_service.training.reference`): the 19 model features of the 21,000 training records,
without IDs, labels or demographic columns, with version and compatibility metadata. The deployed
model is not retrained.

## Decision: provider-aware rule and stopping (backend)

For every feasible provider `g` (available and not attempted in the session):

```text
s_g     = (1 − f_g) · (1 − t_g)
price_g = λ_cost · s_g · cost_g + λ_time · E[time_g]
score_g = s_g · EIG_g − price_g
```

| Symbol | Meaning | Unit |
|---|---|---|
| `f_g` | Configured failure rate | probability |
| `t_g` | Probability that the simulated duration exceeds the timeout | probability |
| `s_g` | Probability that a call returns the features | probability |
| `cost_g` | Configured cost, charged only for a successful call | experimental cost units |
| `E[time_g]` | Mean simulated time charged per call: `E[min(latency + jitter, timeout)]` | seconds |
| `λ_cost` | 0.01 | bits per cost unit |
| `λ_time` | 0.005 | bits per second |

The policy acquires the provider with the highest strictly positive score (ties: configuration
order). When no feasible provider has a positive score, it stops with
`INSUFFICIENT_EXPECTED_BENEFIT`. Unavailable providers are not candidates; failed or timed-out
providers are not retried. Expected benefit and expected cost are weighted by the success
probability because a failed or timed-out call reveals nothing and costs nothing; time is not
weighted, because every attempted call spends it.

The first version (WP2) used `(1 − f_g)·EIG_g − λ_cost·(1 − f_g)·cost_g − λ_time·latency_g`. It was
extended when simulated timeouts and latency jitter were added (WP3); without timeouts and jitter
the two rules are identical. The extension relies on the simulator's order: the timeout is checked
first, and the failure rate applies to calls that did not time out, with failures and jitter drawn
from independent seeded streams. The policy uses only these scenario-derived probabilities, never
the simulated outcome of a particular call. A test compares 20,000 simulated calls with `t_g`,
`s_g` and `E[time_g]`.

The parameters are versioned in `config/policies/adaptive-v1.json` and stored with every decision,
together with the estimates, prices, scores and provider characteristics.

## Exploratory check of the estimator (validation split)

`python -m ml_service.analysis.value_estimator`: 500 validation records (every ninth record),
`K = 100`, every observation state that still has candidates. Labels and true group values were
used only afterwards, offline, to measure what acquisition actually changed. All entropies and log
losses use base-2 logarithms.

| State | Provider | Mean EIG | Median EIG | Realised mean log-loss reduction | Spearman EIG vs realised \|Δp\| | Spearman EIG vs realised log-loss reduction | Mean \|p̄ − p_now\| |
|---|---|---|---|---|---|---|---|
| initial | Repayment | 0.0892 | 0.0938 | 0.0974 | 0.54 | 0.27 | 0.024 |
| initial | Billing | 0.0070 | 0.0033 | 0.0243 | 0.56 | 0.16 | 0.011 |
| initial | Payment | 0.0189 | 0.0152 | 0.0308 | 0.45 | 0.07 | 0.012 |
| after Repayment | Billing | 0.0025 | 0.0016 | 0.0033 | 0.52 | 0.12 | 0.009 |
| after Repayment | Payment | 0.0049 | 0.0037 | 0.0014 | 0.47 | 0.05 | 0.012 |

- At the initial state the highest EIG belongs to Repayment History for 438 of 500 records, Payment
  History for 59 and Billing History for 3: the ranking depends on the record while confirming the
  known dominance of Repayment History (decision 001).
- EIG ranks the size of the realised change of the prediction reasonably (rank correlation
  0.45–0.64 in all states) but the realised reduction of log loss only weakly (0.03–0.32). EIG
  predicts how much the prediction may move, not how much its error falls.
- The diagnostic `p̄ − p_now` compares the current prediction with the mean of the sampled
  predictions; it is small on average (0.009–0.035 over all states) and reaches about 0.11 at the
  95th percentile for Repayment History when other groups are already observed. These are records
  where the neighbor sample or the model's coherence across observation states is weaker.
- One estimate for all candidates takes about 12 ms.

An earlier variant `H(p_now) − mean H(p_k)` could be negative and ranked worse; the `H(p̄)` form
fixes both.

## Consequences

- With the default scenario and these parameters, the price of one neutral call is 0.011 bits. In
  exploratory runs on 60 validation records, the adaptive policy acquired Repayment History for most
  records and then stopped, stopped without any provider for some records and took other providers
  first for a few; making Repayment History expensive, unavailable or unreliable changed the
  choices accordingly.
- Lower cost and time of the adaptive policy in these runs do not show that prediction quality is
  preserved. That requires the Phase 4 comparison on the same records and scenarios, with ROC-AUC,
  PR-AUC, calibration, cost, calls and time, larger samples and uncertainty estimates, and finally
  the frozen test split.

## Limitations

- **One step ahead:** each provider is valued alone; value that appears only when two groups are
  combined is missed.
- **Approximate conditional sampling:** with three initial features the neighbors are a rough
  sample; gains below about 0.005 bits are mostly noise.
- **Depends on the model's probabilities** and on the training and validation data coming from the
  same population.
- **Uncertainty, not decisions:** EIG measures uncertainty reduction; no misclassification costs
  are defined, and the coefficients are not calibrated economic values.

## Reproducing

```bash
cd ml-service
python -m ml_service.training.reference
python -m ml_service.analysis.value_estimator --records 500 --sample-size 100
```
