# Backend

Spring Boot 4.1 application (Java 25) that runs acquisition sessions: it starts a session from a
record's initial information, asks a policy which provider to query next, calls the simulated
provider, gets a prediction from the ML service for the observed values only, and stores every
step in PostgreSQL. See [../docs/architecture.md](../docs/architecture.md) for the design.

## Running

Prerequisites: JDK 25, Docker, and the generated files from the
[main README](../README.md#step-1--prepare-the-data-and-train-the-model-once)
(`data/features/validation.csv`; the ML service needs `ml-service/artifacts`).

```powershell
docker compose up -d          # from the repository root: PostgreSQL
.\mvnw spring-boot:run        # from backend/ (Linux/macOS: ./mvnw spring-boot:run)
```

The API is then on http://localhost:8080. The ML service must be running for sessions to be
created (see [../ml-service/README.md](../ml-service/README.md)).

In IntelliJ IDEA, run `BackendApplication` with the working directory set to `backend` (relative
paths to `../config` and `../data` are resolved from there). `TestBackendApplication` (in the test
sources) starts the backend with a temporary PostgreSQL container instead of the Compose database.

## API

| Method and path | Purpose |
|---|---|
| `GET /api/v1/providers` | Providers, their features and the active scenario's cost, latency, availability and failure rate |
| `GET /api/v1/policies` | Acquisition policies with their versions and decision parameters |
| `POST /api/v1/sessions` `{"recordId", "policyId"}` | Start a session; returns 201 with the initial prediction |
| `GET /api/v1/sessions/{id}` | Session state and history, with the details of every decision and the stop explanation |
| `POST /api/v1/sessions/{id}/advance` | One decision: `ACQUIRED`, `PROVIDER_FAILED` or `STOPPED`, with its reason and details |
| `GET /actuator/health` | Health check |

Policies:

| Policy | Behavior |
|---|---|
| `ADAPTIVE` | Asks the ML service for the expected information gain of each feasible provider and acquires the one with the highest positive score `(1 − f)·EIG − λ_cost·(1 − f)·cost − λ_time·latency`; stops with `INSUFFICIENT_EXPECTED_BENEFIT` when no score is positive. Parameters in `../config/policies/adaptive-v1.json` |
| `FIXED_ORDER` | Providers in configuration order until none is left |
| `FULL_ACQUISITION` | Reference: every available provider, in configuration order. Without budgets it acquires the same providers as `FIXED_ORDER` |

Every decision stores, for each provider, its characteristics, whether it could be chosen and, for
`ADAPTIVE`, its estimate, price and score, together with the policy version and parameters.

Errors are returned as `{"error": "...", "message": "..."}`; the
codes are listed in the architecture document.

## Configuration

Settings are in `src/main/resources/application.yaml`; each can be overridden by an environment
variable:

| Variable | Default | Meaning |
|---|---|---|
| `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | `localhost`, `5432`, `acquisition`, `acquisition`, `acquisition` | Database connection |
| `FEATURE_CONFIG` | `../config/feature-groups.json` | Shared feature grouping |
| `SCENARIO_CONFIG` | `../config/scenarios/default.json` | Provider scenario |
| `ADAPTIVE_POLICY_CONFIG` | `../config/policies/adaptive-v1.json` | Parameters of the adaptive policy |
| `DATASET_FEATURES_DIR` | `../data/features` | Folder with the label-free `<split>.csv` files |
| `DATASET_ACCESS_MODE` | `DEVELOPMENT` | `DEVELOPMENT` serves validation records; `FINAL_EVALUATION` serves test records and requires the Spring profile `final-evaluation` |
| `ML_SERVICE_URL` | `http://localhost:8000` | ML service address |

The application stops at startup if the feature configuration, the scenario, the adaptive policy
settings or the feature file is invalid, or if the access mode and profile do not match. It always runs in UTC.

## Code structure

Packages under `src/main/java/edu/auk/acquisition`:

| Package | Content |
|---|---|
| `api` | REST controllers, request and response types, error handling |
| `session` | `SessionService`, `AcquisitionOrchestrator`, `InitialInformationLoader`, session model |
| `policy` | `AcquisitionPolicy`, `AcquisitionContext`, `AcquisitionDecision`, `DecisionDetails`, `AdaptivePolicy` and its settings, `FixedOrderPolicy`, `FullAcquisitionPolicy` |
| `provider` | `InformationProvider`, `ProviderRegistry`, `ProviderExecutor` |
| `provider.simulated` | `SimulatedProvider` |
| `scenario` | `ProviderScenario`, scenario loading and validation |
| `prediction` | `PredictionClient` (prediction and value estimates) and its HTTP implementation |
| `dataset` | `RestrictedRecordReader`, dataset access modes |
| `feature` | `FeatureConfiguration`, `ObservedFeatures` |
| `persistence` | `JdbcSessionRepository` (SQL with `JdbcClient`) |
| `config` | Application properties, clock |

Database migrations are in `src/main/resources/db/migration` (Flyway).

## Tests

```powershell
.\mvnw test
```

Docker must be running: database tests start a real PostgreSQL 18 with Testcontainers. Test data
comes from small fixture files in `src/test/resources`, so the tests do not need the prepared
dataset. Tests that compare against the real prepared data run only when it exists.

The ML service contract tests run only when the service is up:

```powershell
$env:ML_SERVICE_URL = "http://localhost:8000"; .\mvnw test -Dtest=MlServiceContractTest
```

`ArchitectureTest` checks the package dependency rules on every build.

## Docker image

`Dockerfile` builds the application with the Maven Wrapper and runs it on a Java 25 runtime image
as a non-root user. It is used by the `app` profile of `../compose.yaml`, which mounts only the
configuration (feature grouping, scenarios, policy settings) and `data/features/validation.csv`,
read-only.
