package edu.auk.acquisition.session;

/** What one {@code /advance} call did. */
public record AdvanceResult(Outcome outcome, String decisionReason, AcquisitionStep step, SessionHistory history) {

	public enum Outcome {

		/** A provider was queried successfully and a new prediction was made. */
		ACQUIRED,

		/** A provider was queried but failed; no new information and no new prediction. */
		PROVIDER_FAILED,

		/** The policy stopped; the session is completed. */
		STOPPED

	}

}
