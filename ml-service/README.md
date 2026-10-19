# ML service

Python 3.13 package `ml_service` with two separate parts:

- **Offline pipeline:** dataset preparation, model training and selection, provider-value
  analysis. Works with complete records and labels.
- **Online inference:** a FastAPI service that predicts the probability of default from observed
  values and their mask. It loads only the trained model and imports none of the offline code.

## Setup

Windows (PowerShell), from `ml-service/`:

```powershell
py -3.13 -m venv .venv
.venv\Scripts\python -m pip install -r requirements-dev.txt
.venv\Scripts\python -m pip install -e .
```

On Linux / macOS use `python3.13 -m venv .venv` and `.venv/bin/python`. All dependencies, direct
and indirect, are pinned in `requirements.txt` (runtime) and `requirements-dev.txt` (tests).

## Commands

| Command | Result |
|---|---|
| `python -m ml_service.data.prepare` | Downloads the dataset if needed, verifies its checksum, applies the committed split and writes `../data/features/` and `../data/restricted/` |
| `python -m ml_service.training.train` | Trains full-information and mask-aware candidates on the training split, evaluates them on the validation split, selects the deployed model and writes `artifacts/` |
| `python -m ml_service.analysis.provider_value` | Contribution of each provider for the deployed model, on the validation split |
| `python -m ml_service.inference` | Starts the prediction service on http://127.0.0.1:8000 (`--host`, `--port` to change) |

Use the virtual environment's Python (`.venv\Scripts\python` or `.venv/bin/python`). Training is
deterministic: the same data, configuration and settings give the same metrics and the same model
version. The test split is never read by training or analysis.

`artifacts/` contains `deployed.json` (the selected model), one folder per model version with the
model and its metadata (feature configuration version, dataset checksum, split manifest hash,
library versions, validation metrics), and `validation-report-<feature config>.json`.

## Prediction API

`POST /internal/v1/predict`

```json
{
  "observedFeatures": {"LIMIT_BAL": 50000, "BILL_AMT1": 46990, "PAY_AMT1": 2000},
  "observedMask": ["LIMIT_BAL", "BILL_AMT1", "PAY_AMT1"]
}
```

Response: `{"probability": 0.256..., "modelVersion": "...", "featureConfigVersion": "fg-2"}`.

The mask must list exactly the keys of `observedFeatures` and form a legal state: all initial
features plus whole provider groups. Values must be numbers. Anything else (partial groups, the
target, demographic columns, unknown fields, text values, NaN) is rejected with HTTP 422 and
`{"error": "INVALID_OBSERVATION" | "INVALID_REQUEST", "message": ...}`. `GET /health` returns the
model and feature configuration versions. The service does not start if the deployed model was
trained for a different feature configuration.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `PAA_FEATURE_CONFIG` | `../config/feature-groups.json` | Feature grouping |
| `PAA_DATA_DIR` | `../data` | Data folder (offline pipeline only) |
| `PAA_ARTIFACTS_DIR` | `artifacts` | Model artifacts |

## Code structure

| Module | Content |
|---|---|
| `config.py` | Loads and validates the shared feature grouping; legal observation states |
| `encoding.py` | Turns an observation state into model input (hidden groups missing, group indicators) |
| `data/prepare.py` | Dataset download, validation, split and output files |
| `training/` | Candidate models, training, selection, artifacts |
| `analysis/provider_value.py` | Provider contribution analysis |
| `inference/` | FastAPI application, request validation, predictor |
| `artifacts.py`, `metrics.py`, `transforms.py`, `paths.py` | Shared helpers |

## Tests

```powershell
.venv\Scripts\python -m pytest
```

Most tests use small synthetic datasets. Tests marked `requires_data` check the real prepared data
and the deployed model; they are skipped until the two pipeline commands have been run.

## Docker image

`Dockerfile` contains only the code and runtime dependencies. In `../compose.yaml` the service
mounts the feature configuration and `artifacts/` read-only, has no access to any dataset files
and is reachable only from the backend.
