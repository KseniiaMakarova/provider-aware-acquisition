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
 * @param executionTime      real processing time of the call inside the backend
 */
public record ProviderAttempt(String providerId, Status status, Map<String, Double> values, BigDecimal configuredCost,
		BigDecimal chargedCost, long simulatedLatencyMs, Duration executionTime, String failureReason) {

	public enum Status {

		SUCCESS, FAILED

	}

	public ProviderAttempt {
		values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	public boolean succeeded() {
		return status == Status.SUCCESS;
	}

}
