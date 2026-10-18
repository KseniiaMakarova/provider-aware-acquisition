package edu.auk.acquisition.provider;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ProviderGroup;
import edu.auk.acquisition.scenario.ProviderScenario;
import edu.auk.acquisition.scenario.ProviderSettings;

/**
 * The configured providers and their characteristics in the active scenario.
 *
 * <p>Provider definitions (which features) come from the feature configuration; cost, latency
 * and availability come from the scenario, so either can change without the other.
 */
public class ProviderRegistry {

	private final FeatureConfiguration config;
	private final ProviderScenario scenario;
	private final Map<String, InformationProvider> providers = new HashMap<>();

	public ProviderRegistry(FeatureConfiguration config, ProviderScenario scenario,
			Collection<? extends InformationProvider> implementations) {
		this.config = config;
		this.scenario = scenario;
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

	public ProviderScenario scenario() {
		return scenario;
	}

	/** All configured providers in feature-configuration order. */
	public List<ProviderMetadata> metadata() {
		return config.providers().stream().map(this::metadataOf).toList();
	}

	public Optional<ProviderMetadata> metadata(String providerId) {
		return config.provider(providerId).map(this::metadataOf);
	}

	/** Available providers that have not been attempted yet in this session (FR-5, FR-9). */
	public List<ProviderMetadata> feasibleProviders(Collection<String> attempted) {
		return metadata().stream().filter(ProviderMetadata::available).filter(m -> !attempted.contains(m.id())).toList();
	}

	InformationProvider implementation(String providerId) {
		return providers.get(providerId);
	}

	private ProviderMetadata metadataOf(ProviderGroup group) {
		ProviderSettings settings = scenario.settings(group.id());
		return new ProviderMetadata(group.id(), group.description(), group.features(), settings.cost(),
				settings.latencyMs(), settings.available());
	}

}
