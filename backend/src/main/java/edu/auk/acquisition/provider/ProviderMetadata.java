package edu.auk.acquisition.provider;

import java.math.BigDecimal;
import java.util.List;

/**
 * Public information about a provider in the active scenario: what it supplies and what it
 * costs. Policies and the API see only this, never feature values.
 */
public record ProviderMetadata(String id, String description, List<String> suppliedFeatures, BigDecimal cost,
		long expectedLatencyMs, boolean available) {

	public ProviderMetadata {
		suppliedFeatures = List.copyOf(suppliedFeatures);
	}

}
