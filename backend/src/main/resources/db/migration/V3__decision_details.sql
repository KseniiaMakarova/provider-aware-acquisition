-- Explanations of policy decisions (Phase 3).
--
-- Every decision stores the policy's view of all providers: characteristics, whether they could
-- be chosen, value estimates and scores (if the policy uses them), parameters and versions. The
-- details contain no feature values.

-- Details of the decision that chose this provider.
ALTER TABLE acquisition_step ADD COLUMN decision_details JSONB;

-- Why the session stopped, in words, and the details of the stop decision.
ALTER TABLE acquisition_session ADD COLUMN stop_explanation TEXT;
ALTER TABLE acquisition_session ADD COLUMN stop_decision_details JSONB;

UPDATE acquisition_session
SET stop_explanation = 'Stopped before stop explanations were recorded'
WHERE status <> 'ACTIVE' AND stop_explanation IS NULL;

ALTER TABLE acquisition_session ADD CONSTRAINT finished_session_has_stop_explanation
    CHECK (status = 'ACTIVE' OR stop_explanation IS NOT NULL);
