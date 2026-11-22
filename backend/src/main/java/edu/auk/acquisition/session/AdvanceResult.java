package edu.auk.acquisition.session;

import edu.auk.acquisition.policy.DecisionDetails;

/** What one {@code /advance} call did, and the details of the decision behind it. */
public record AdvanceResult(Outcome outcome, String decisionReason, DecisionDetails decision, AcquisitionStep step,
		SessionHistory history) {

	public enum Outcome {

		/** A provider was queried successfully and a new prediction was made. */
		ACQUIRED,

		/** A provider was queried but failed; no new information and no new prediction. */
		PROVIDER_FAILED,

		/** The policy stopped; the session is completed. */
		STOPPED

	}

}
