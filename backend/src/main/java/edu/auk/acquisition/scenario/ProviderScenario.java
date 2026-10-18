package edu.auk.acquisition.scenario;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import edu.auk.acquisition.feature.FeatureConfiguration;

/**
 * A reproducible set of provider characteristics (cost, latency, availability, failure rate)
 * plus the seed for simulated failures. Changing the scenario never changes the feature
 * grouping or the predictive model (FR-15, ER-8, ER-11).
 */
public record ProviderScenario(String version, String description, long seed, Map<String, ProviderSettings> providers) {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	public ProviderScenario {
		providers = Map.copyOf(providers);
	}

	public ProviderSettings settings(String providerId) {
		ProviderSettings settings = providers.get(providerId);
		if (settings == null) {
			throw new IllegalArgumentException("Scenario " + version + " has no settings for provider " + providerId);
		}
		return settings;
	}

	/** Loads a scenario file and checks it covers exactly the configured providers with valid values. */
	public static ProviderScenario load(Path path, FeatureConfiguration config) {
		RawScenario raw;
		try {
			raw = MAPPER.readValue(Files.readString(path), RawScenario.class);
		}
		catch (IOException | JacksonException e) {
			throw new ScenarioException("Cannot read scenario " + path.toAbsolutePath(), e);
		}
		if (raw.scenarioVersion() == null || raw.scenarioVersion().isBlank()) {
			throw new ScenarioException("scenarioVersion must not be empty in " + path);
		}
		if (raw.seed() == null || raw.providers() == null) {
			throw new ScenarioException("Scenario " + raw.scenarioVersion() + " needs a seed and provider settings");
		}

		TreeSet<String> missing = new TreeSet<>(config.providerIds());
		missing.removeAll(raw.providers().keySet());
		TreeSet<String> unknown = new TreeSet<>(raw.providers().keySet());
		config.providerIds().forEach(unknown::remove);
		if (!missing.isEmpty() || !unknown.isEmpty()) {
			throw new ScenarioException("Scenario " + raw.scenarioVersion() + " must define exactly the configured providers;"
					+ " missing " + missing + ", unknown " + unknown);
		}

		raw.providers().forEach((id, s) -> {
			if (s.cost() == null || s.cost().signum() < 0) {
				throw new ScenarioException(id + ": cost must be zero or positive");
			}
			if (s.latencyMs() == null || s.latencyMs() < 0) {
				throw new ScenarioException(id + ": latencyMs must be zero or positive");
			}
			if (s.available() == null) {
				throw new ScenarioException(id + ": available must be true or false");
			}
			if (s.failureRate() == null || s.failureRate() < 0 || s.failureRate() > 1) {
				throw new ScenarioException(id + ": failureRate must be between 0 and 1");
			}
		});

		Map<String, ProviderSettings> settings = new HashMap<>();
		raw.providers()
			.forEach((id, s) -> settings.put(id,
					new ProviderSettings(s.cost(), s.latencyMs(), s.available(), s.failureRate())));
		return new ProviderScenario(raw.scenarioVersion(), raw.description(), raw.seed(), settings);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawScenario(String scenarioVersion, String description, Long seed, Map<String, RawSettings> providers) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawSettings(BigDecimal cost, Long latencyMs, Boolean available, Double failureRate) {
	}

}
