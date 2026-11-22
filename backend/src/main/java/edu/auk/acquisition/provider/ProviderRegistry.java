package edu.auk.acquisition.provider;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ProviderGroup;
import edu.auk.acquisition.scenario.ProviderScenario;

/**
 * The configured providers and their implementations.
 *
 * <p>Provider definitions (which features) come from the feature configuration; cost, latency,
 * timeout and availability come from a scenario, so either can change without the other. Each
 * session runs with its own scenario.
 */
public class ProviderRegistry {

	private final FeatureConfiguration config;

	private final Map<String, InformationProvider> providers = new HashMap<>();

	public ProviderRegistry(FeatureConfiguration config, Collection<? extends InformationProvider> implementations) {
		this.config = config;
		for (InformationProvider provider : implementations) {
			if (providers.put(provider.id(), provider) != null) {
				throw new IllegalStateException("More than one implementation for provider " + provider.id());
			}
		}
		for (ProviderGroup group : config.providers()) {
			InformationProvider provider = providers.get(group.id());
			if (provider == null) {
				throw new IllegalStateException("No implementation for provider " + group.id());
			}
			if (!provider.suppliedFeatures().equals(group.features())) {
				throw new IllegalStateException("Provider " + group.id() + " supplies " + provider.suppliedFeatures()
						+ " but the feature configuration declares " + group.features());
			}
		}
		if (providers.size() != config.providers().size()) {
			throw new IllegalStateException("Implementations for providers that are not configured: " + providers.keySet());
		}
	}

	/** All configured providers in feature-configuration order, with their characteristics in the scenario. */
	public List<ProviderMetadata> metadata(ProviderScenario scenario) {
		return config.providers().stream().map(g -> ProviderMetadata.of(g, scenario.settings(g.id()))).toList();
	}

	public Optional<ProviderMetadata> metadata(String providerId, ProviderScenario scenario) {
		return config.provider(providerId).map(g -> ProviderMetadata.of(g, scenario.settings(g.id())));
	}

	/** Available providers that have not been attempted yet in this session (FR-5, FR-9). */
	public List<ProviderMetadata> feasibleProviders(ProviderScenario scenario, Collection<String> attempted) {
		return metadata(scenario).stream()
			.filter(ProviderMetadata::available)
			.filter(m -> !attempted.contains(m.id()))
			.toList();
	}

	InformationProvider implementation(String providerId) {
		return providers.get(providerId);
	}

}
