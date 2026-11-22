-- Scenarios chosen per session and simulated timeouts (Phase 3).

-- Snapshot of the scenario a session runs with (characteristics of every provider and the seed), so
-- later changes to scenario files do not change how the session is interpreted. Sessions stored
-- before this migration have no snapshot and use the configured scenario of the same version.
ALTER TABLE acquisition_session ADD COLUMN scenario_snapshot JSONB;

-- Why a failed call failed: a reported failure or a simulated timeout.
ALTER TABLE acquisition_step ADD COLUMN failure_type VARCHAR(16);

UPDATE acquisition_step SET failure_type = 'FAILURE' WHERE status = 'FAILED' AND failure_type IS NULL;

ALTER TABLE acquisition_step ADD CONSTRAINT failure_type_matches_status CHECK (
    (status = 'SUCCESS' AND failure_type IS NULL)
    OR (status = 'FAILED' AND failure_type IS NOT NULL AND failure_type IN ('FAILURE', 'TIMEOUT'))
);
