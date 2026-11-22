package edu.auk.acquisition.policy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ObservedFeatures;
import edu.auk.acquisition.prediction.ValueEstimate;
import edu.auk.acquisition.provider.ProviderMetadata;

/** Builds policy contexts for unit tests. */
final class PolicyContexts {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	private PolicyContexts() {
	}

	static ProviderMetadata provider(String id, double cost, long latencyMs, boolean available, double failureRate) {
		return new ProviderMetadata(id, "", config.provider(id).orElseThrow().features(), BigDecimal.valueOf(cost),
				latencyMs, available, failureRate);
	}

	/** The neutral scenario: cost 1, 200 ms, available, never fails. */
	static ProviderMetadata neutral(String id) {
		return provider(id, 1, 200, true, 0);
	}

	static List<ProviderMetadata> neutralProviders() {
		return config.providerIds().stream().map(PolicyContexts::neutral).toList();
	}

	/** Neutral providers with some replaced, in configuration order. */
	static List<ProviderMetadata> providers(ProviderMetadata... replacements) {
		List<ProviderMetadata> providers = new ArrayList<>(neutralProviders());
		for (ProviderMetadata r : replacements) {
			providers.replaceAll(p -> p.id().equals(r.id()) ? r : p);
		}
		return providers;
	}

	/**
	 * @param acquired  providers whose groups are observed (values 1.0)
	 * @param attempted providers already attempted (acquired ones and failed ones)
	 * @param estimate  value estimate given to the policy, or {@code null}
	 */
	static AcquisitionContext context(List<String> acquired, List<String> attempted, List<ProviderMetadata> providers,
			ValueEstimate estimate) {
		Map<String, Double> values = new HashMap<>();
		config.observedFeatures(acquired).forEach(f -> values.put(f, 1.0));
		List<ProviderMetadata> feasible = providers.stream()
			.filter(ProviderMetadata::available)
			.filter(p -> !attempted.contains(p.id()))
			.toList();
		return new AcquisitionContext(ObservedFeatures.of(values, config), 0.3, providers, feasible, attempted,
				BigDecimal.ZERO, 0, estimate);
	}

	static AcquisitionContext initial(List<ProviderMetadata> providers, ValueEstimate estimate) {
		return context(List.of(), List.of(), providers, estimate);
	}

	/** An estimate with the given expected information gains, in the order given. */
	static ValueEstimate estimate(Object... providerAndGain) {
		List<ValueEstimate.ProviderValue> values = new ArrayList<>();
		for (int i = 0; i < providerAndGain.length; i += 2) {
			values.add(new ValueEstimate.ProviderValue((String) providerAndGain[i], (Double) providerAndGain[i + 1], 0.3,
					0.05));
		}
		return new ValueEstimate("knn-eig-v1", 100, "ref-test", "mask-hgb-test", 0.3, values);
	}

}
