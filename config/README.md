# Shared configuration

Files read by both the backend (Java) and the ML service (Python). Both sides validate them with
the same rules and refuse to start if a file is invalid.

## `feature-groups.json`

Defines which features are known when a session starts and which features each provider reveals.

| Field | Meaning |
|---|---|
| `configVersion` | Version of the grouping (currently `fg-2`); stored with every model, session and prediction |
| `dataset.recordIdColumn`, `dataset.targetColumn` | Record identifier and target; never model inputs |
| `initialFeatures` | Features observed at the start of every session |
| `providers` | Provider ID, description and the exact features one call reveals |
| `excludedColumns` | Columns that are never used (the demographic attributes) |

Rules: every feature belongs to exactly one place (initial features or one provider); the ID,
target and excluded columns may not be features. A trained model records the version it was
trained with, and the ML service refuses to run with a different version.

`archive/feature-groups-fg-1.json` is the earlier grouping, kept to reproduce the comparison in
[../docs/decisions/001-feature-grouping-fg-2.md](../docs/decisions/001-feature-grouping-fg-2.md).

## `scenarios/`

A scenario sets the operational characteristics of each provider without touching the feature
grouping or the model:

```json
{
  "scenarioVersion": "default-v1",
  "seed": 42,
  "providers": {
    "REPAYMENT_HISTORY": { "cost": 1.0, "latencyMs": 200, "available": true, "failureRate": 0.0 }
  }
}
```

| Field | Meaning |
|---|---|
| `cost` | Charged for a successful call, in experimental units |
| `latencyMs` | Simulated duration of a call; recorded, not waited for |
| `available` | Unavailable providers are never selected or called |
| `failureRate` | Share of calls that fail; decided from `seed`, the record and the provider, so results repeat exactly |

A scenario must define every configured provider. `default.json` is neutral: all providers have the
same cost and latency, are available and never fail. To use another scenario, add a file here and
set `SCENARIO_CONFIG` for the backend, or `SCENARIO=<file name without .json>` for Docker Compose.
