package edu.auk.acquisition.provider;

import java.math.BigDecimal;
import java.util.List;

import edu.auk.acquisition.feature.ProviderGroup;
import edu.auk.acquisition.scenario.ProviderSettings;

/**
 * Public information about a provider in a scenario: what it supplies, what it costs, how long
 * it takes and how reliable it is. Policies and the API see only this, never feature values.
 *
 * @param latencyMs          simulated duration of a call without jitter
 * @param latencyJitterMs    largest extra simulated duration of a call
 * @param timeoutMs          simulated timeout, or {@code null} for none
 * @param failureRate        probability that a call fails, as configured
 * @param timeoutProbability probability that a call exceeds the timeout
 * @param expectedLatencyMs  mean simulated time a call adds to the session (capped at the timeout)
 * @param successProbability probability that a call returns the features: {@code (1 − failureRate)·(1 − timeoutProbability)}
 */
public record ProviderMetadata(String id, String description, List<String> suppliedFeatures, BigDecimal cost,
		long latencyMs, long latencyJitterMs, Long timeoutMs, boolean available, double failureRate,
		double timeoutProbability, double expectedLatencyMs, double successProbability) {

	public ProviderMetadata {
		suppliedFeatures = List.copyOf(suppliedFeatures);
	}

	public static ProviderMetadata of(ProviderGroup group, ProviderSettings settings) {
		return new ProviderMetadata(group.id(), group.description(), group.features(), settings.cost(),
				settings.latencyMs(), settings.latencyJitterMs(), settings.timeoutMs(), settings.available(),
				settings.failureRate(), settings.timeoutProbability(), settings.expectedChargedLatencyMs(),
				settings.successProbability());
	}

}
