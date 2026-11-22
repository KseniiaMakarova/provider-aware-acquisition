package edu.auk.acquisition.provider;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recorded outcome of one provider call, as charged under the scenario's rules.
 *
 * @param values             the provider's features on success; empty on failure
 * @param configuredCost     the scenario cost at the time of the call
 * @param chargedCost        what this attempt adds to the session's total cost
 * @param simulatedLatencyMs simulated provider time, accumulated whether or not the call succeeded
 *                           (for a timeout: the timeout)
 * @param executionTime      real processing time of the call inside the backend
 * @param failureType        why a failed call failed; {@code null} on success
 */
public record ProviderAttempt(String providerId, Status status, Map<String, Double> values, BigDecimal configuredCost,
		BigDecimal chargedCost, long simulatedLatencyMs, Duration executionTime, FailureType failureType,
		String failureReason) {

	public enum Status {

		SUCCESS, FAILED

	}

	public enum FailureType {

		/** The provider reported a (temporary) failure. */
		FAILURE,

		/** The simulated call took longer than the provider's timeout. */
		TIMEOUT

	}

	public ProviderAttempt {
		values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	public boolean succeeded() {
		return status == Status.SUCCESS;
	}

}
