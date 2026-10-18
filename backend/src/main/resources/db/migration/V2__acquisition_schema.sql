-- Acquisition sessions, provider attempts and prediction snapshots.
--
-- Only information visible to the session is stored: the observed feature values and the
-- providers acquired so far. Complete dataset records and labels are never persisted.

CREATE TABLE acquisition_session (
    id                        UUID         PRIMARY KEY,
    record_id                 VARCHAR(64)  NOT NULL,
    dataset_split             VARCHAR(16)  NOT NULL CHECK (dataset_split IN ('validation', 'test')),
    access_mode               VARCHAR(32)  NOT NULL CHECK (access_mode IN ('DEVELOPMENT', 'FINAL_EVALUATION')),
    policy_id                 VARCHAR(64)  NOT NULL,
    scenario_version          VARCHAR(64)  NOT NULL,
    feature_config_version    VARCHAR(32)  NOT NULL,
    model_version             VARCHAR(128) NOT NULL,
    status                    VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED', 'FAILED')),
    stop_reason               VARCHAR(64),
    -- Observed values keyed by feature name; the mask is the initial features plus the
    -- features of the acquired providers.
    observed_features         JSONB        NOT NULL,
    acquired_providers        JSONB        NOT NULL DEFAULT '[]',
    total_cost                NUMERIC(14, 4) NOT NULL DEFAULT 0 CHECK (total_cost >= 0),
    accumulated_simulated_ms  BIGINT       NOT NULL DEFAULT 0 CHECK (accumulated_simulated_ms >= 0),
    execution_ms              BIGINT       NOT NULL DEFAULT 0 CHECK (execution_ms >= 0),
    -- Atomic claim that lets only one /advance run for a session at a time.
    advance_token             UUID,
    advance_claimed_at        TIMESTAMPTZ,
    version                   BIGINT       NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ  NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    CONSTRAINT completed_session_has_stop_reason CHECK (status = 'ACTIVE' OR stop_reason IS NOT NULL),
    CONSTRAINT claim_fields_set_together CHECK ((advance_token IS NULL) = (advance_claimed_at IS NULL))
);

CREATE INDEX acquisition_session_record_idx ON acquisition_session (record_id);

-- One row per provider call attempt, in order.
CREATE TABLE acquisition_step (
    id                          UUID          PRIMARY KEY,
    session_id                  UUID          NOT NULL REFERENCES acquisition_session (id) ON DELETE CASCADE,
    step_number                 INTEGER       NOT NULL CHECK (step_number >= 1),
    provider_id                 VARCHAR(64)   NOT NULL,
    decision_reason             TEXT,
    status                      VARCHAR(16)   NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    failure_reason              TEXT,
    -- Only the features of this provider, and only on success.
    returned_features           JSONB,
    configured_cost             NUMERIC(14, 4) NOT NULL CHECK (configured_cost >= 0),
    charged_cost                NUMERIC(14, 4) NOT NULL CHECK (charged_cost >= 0),
    simulated_latency_ms        BIGINT        NOT NULL CHECK (simulated_latency_ms >= 0),
    execution_ms                BIGINT        NOT NULL CHECK (execution_ms >= 0),
    prediction_sequence_number  INTEGER,
    created_at                  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT step_number_unique UNIQUE (session_id, step_number),
    -- A provider is attempted at most once per session; failed providers are not retried.
    CONSTRAINT provider_attempted_once UNIQUE (session_id, provider_id),
    CONSTRAINT success_has_features CHECK (
        (status = 'SUCCESS' AND returned_features IS NOT NULL AND failure_reason IS NULL)
        OR (status = 'FAILED' AND returned_features IS NULL AND failure_reason IS NOT NULL)
    )
);

-- Predictions are appended, never overwritten: sequence 0 is the initial prediction.
CREATE TABLE prediction_snapshot (
    id                      UUID              PRIMARY KEY,
    session_id              UUID              NOT NULL REFERENCES acquisition_session (id) ON DELETE CASCADE,
    sequence_number         INTEGER           NOT NULL CHECK (sequence_number >= 0),
    probability             DOUBLE PRECISION  NOT NULL CHECK (probability >= 0 AND probability <= 1),
    observed_mask           JSONB             NOT NULL,
    model_version           VARCHAR(128)      NOT NULL,
    feature_config_version  VARCHAR(32)       NOT NULL,
    created_at              TIMESTAMPTZ       NOT NULL,
    CONSTRAINT sequence_number_unique UNIQUE (session_id, sequence_number)
);
