package edu.auk.acquisition.policy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import edu.auk.acquisition.feature.ObservedFeatures;
import edu.auk.acquisition.prediction.ValueEstimate;
import edu.auk.acquisition.provider.ProviderMetadata;

/**
 * Everything a policy may know when deciding. It deliberately contains no record identifier,
 * hidden values, label or data access: only the observed state, the current prediction, public
 * provider metadata and, for policies that ask for it, the value estimate made from the observed
 * state.
 *
 * @param observed           observed values with their explicit mask
 * @param probability        latest predicted probability of default
 * @param providers          all configured providers with their scenario characteristics, in configuration order
 * @param feasibleProviders  available providers not yet attempted, in configuration order
 * @param attemptedProviders providers already attempted, successfully or not
 * @param valueEstimate      estimates for the feasible providers, or {@code null} if the policy needs none
 *                           or no provider is feasible
 */
public record AcquisitionContext(ObservedFeatures observed, double probability, List<ProviderMetadata> providers,
		List<ProviderMetadata> feasibleProviders, List<String> attemptedProviders, BigDecimal totalCost,
		long accumulatedSimulatedMs, ValueEstimate valueEstimate) {

	public AcquisitionContext {
		providers = List.copyOf(providers);
		feasibleProviders = List.copyOf(feasibleProviders);
		attemptedProviders = List.copyOf(attemptedProviders);
	}

	public Set<String> acquiredProviders() {
		return observed.acquiredProviders();
	}

	/** All configured provider IDs, in configuration order. */
	public List<String> configuredProviders() {
		return providers.stream().map(ProviderMetadata::id).toList();
	}

	public Optional<ValueEstimate> estimate() {
		return Optional.ofNullable(valueEstimate);
	}

}
