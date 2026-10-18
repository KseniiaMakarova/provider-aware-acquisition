package edu.auk.acquisition.policy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import edu.auk.acquisition.feature.ObservedFeatures;
import edu.auk.acquisition.provider.ProviderMetadata;

/**
 * Everything a policy may know when deciding. It deliberately contains no record identifier,
 * hidden values, label or data access: only the observed state, the current prediction and
 * public provider metadata.
 *
 * @param observed            observed values with their explicit mask
 * @param probability         latest predicted probability of default
 * @param feasibleProviders   available providers not yet attempted, in configuration order
 * @param configuredProviders all configured provider IDs, in configuration order
 * @param attemptedProviders  providers already attempted, successfully or not
 */
public record AcquisitionContext(ObservedFeatures observed, double probability, List<ProviderMetadata> feasibleProviders,
		List<String> configuredProviders, List<String> attemptedProviders, BigDecimal totalCost,
		long accumulatedSimulatedMs) {

	public AcquisitionContext {
		feasibleProviders = List.copyOf(feasibleProviders);
		configuredProviders = List.copyOf(configuredProviders);
		attemptedProviders = List.copyOf(attemptedProviders);
	}

	public Set<String> acquiredProviders() {
		return observed.acquiredProviders();
	}

}
