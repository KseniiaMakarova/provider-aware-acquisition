package edu.auk.acquisition.policy;

/**
 * One policy decision: query a provider next, or stop. Each decision carries a short reason
 * and the details needed to explain it later (they are stored with the step or the session).
 */
public sealed interface AcquisitionDecision {

	String reason();

	DecisionDetails details();

	record Acquire(String providerId, String reason, DecisionDetails details) implements AcquisitionDecision {
	}

	record Stop(StopReason stopReason, String reason, DecisionDetails details) implements AcquisitionDecision {
	}

}
