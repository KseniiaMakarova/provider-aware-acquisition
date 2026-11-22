package edu.auk.acquisition.provider;

import java.math.BigDecimal;
import java.util.List;

/**
 * Public information about a provider in the active scenario: what it supplies, what it costs,
 * how long it takes and how reliable it is. Policies and the API see only this, never feature
 * values.
 *
 * @param failureRate probability that a call fails, as configured in the scenario
 */
public record ProviderMetadata(String id, String description, List<String> suppliedFeatures, BigDecimal cost,
		long expectedLatencyMs, boolean available, double failureRate) {

	public ProviderMetadata {
		suppliedFeatures = List.copyOf(suppliedFeatures);
	}

}
