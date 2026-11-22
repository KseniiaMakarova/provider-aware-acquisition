package edu.auk.acquisition.policy;

import java.util.Map;
import java.util.OptionalInt;

/**
 * Chooses the next provider to query or decides to stop (FR-7, FR-8, FR-10). A policy never
 * calls providers, the ML service or the database; the orchestrator does that.
 */
public interface AcquisitionPolicy {

	String id();

	/** Version of the policy and its parameters, stored with every decision. */
	String version();

	String description();

	/** Parameters that affect decisions, stored with every decision. */
	default Map<String, Object> parameters() {
		return Map.of();
	}

	/**
	 * If present, the orchestrator asks the ML service for value estimates of the feasible
	 * providers, using this many reference records, and passes them in the context.
	 */
	default OptionalInt valueSampleSize() {
		return OptionalInt.empty();
	}

	AcquisitionDecision selectNext(AcquisitionContext context);

}
