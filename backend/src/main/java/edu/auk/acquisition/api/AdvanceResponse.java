package edu.auk.acquisition.api;

import edu.auk.acquisition.api.SessionResponse.StepView;
import edu.auk.acquisition.policy.DecisionDetails;

/**
 * Result of one {@code /advance}: what was decided and why, the details of the decision, the
 * provider attempt if any, and the session afterwards.
 *
 * @param outcome ACQUIRED, PROVIDER_FAILED or STOPPED
 */
public record AdvanceResponse(String outcome, String decisionReason, DecisionDetails decision, StepView step,
		SessionResponse session) {
}
