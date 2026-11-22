package edu.auk.acquisition.scenario;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import edu.auk.acquisition.feature.FeatureConfiguration;

/**
 * A reproducible set of provider characteristics (cost, latency, jitter, timeout, availability,
 * failure rate) plus the seed for simulated failures and jitter. Changing the scenario never
 * changes the feature grouping or the predictive model (FR-15, ER-8, ER-11).
 *
 * <p>A session keeps a snapshot of its scenario (see {@link #toJson()}), so editing a scenario
 * file later does not change how earlier sessions are interpreted.
 */
public record ProviderScenario(String version, String description, long seed, Map<String, ProviderSettings> providers) {

	/** Longest allowed simulated jitter, so expected latencies stay cheap to compute. */
	static final long MAX_JITTER_MS = 600_000;

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	public ProviderScenario {
		providers = Collections.unmodifiableMap(new TreeMap<>(providers));
	}

	public ProviderSettings settings(String providerId) {
		ProviderSettings settings = providers.get(providerId);
		if (settings == null) {
			throw new IllegalArgumentException("Scenario " + version + " has no settings for provider " + providerId);
		}
		return settings;
	}

	/** The scenario as stored with a session. */
	public String toJson() {
		return MAPPER.writeValueAsString(this);
	}

	public static ProviderScenario fromJson(String json) {
		return MAPPER.readValue(json, ProviderScenario.class);
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
			if (s.timeoutMs() != null && s.timeoutMs() <= 0) {
				throw new ScenarioException(id + ": timeoutMs must be positive when given");
			}
			if (s.latencyJitterMs() != null && (s.latencyJitterMs() < 0 || s.latencyJitterMs() > MAX_JITTER_MS)) {
				throw new ScenarioException(id + ": latencyJitterMs must be between 0 and " + MAX_JITTER_MS);
			}
		});
		Map<String, ProviderSettings> settings = new TreeMap<>();
		raw.providers()
			.forEach((id, s) -> settings.put(id, new ProviderSettings(s.cost(), s.latencyMs(), s.available(),
					s.failureRate(), s.timeoutMs(), s.latencyJitterMs() == null ? 0 : s.latencyJitterMs())));
		return new ProviderScenario(raw.scenarioVersion(), raw.description(), raw.seed(), settings);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawScenario(String scenarioVersion, String description, Long seed, Map<String, RawSettings> providers) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawSettings(BigDecimal cost, Long latencyMs, Boolean available, Double failureRate, Long timeoutMs,
			Long latencyJitterMs) {
	}

}
