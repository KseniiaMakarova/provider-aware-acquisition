package edu.auk.acquisition.api;

import edu.auk.acquisition.api.SessionResponse.StepView;

/**
 * Result of one {@code /advance}: what was decided and why, the provider attempt if any, and
 * the session afterwards.
 *
 * @param outcome ACQUIRED, PROVIDER_FAILED or STOPPED
 */
public record AdvanceResponse(String outcome, String decisionReason, StepView step, SessionResponse session) {

}
