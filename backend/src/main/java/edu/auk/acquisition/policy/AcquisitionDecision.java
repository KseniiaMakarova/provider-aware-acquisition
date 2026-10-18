package edu.auk.acquisition.policy;

/** One policy decision: query a provider next, or stop. */
public sealed interface AcquisitionDecision {

	String reason();

	record Acquire(String providerId, String reason) implements AcquisitionDecision {
	}

	record Stop(StopReason stopReason, String reason) implements AcquisitionDecision {
	}

}
