package edu.auk.acquisition;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import edu.auk.acquisition.scenario.ProviderScenario;
import edu.auk.acquisition.scenario.ProviderSettings;

/** Provider scenarios for tests. */
public final class TestScenarios {

	public static final Path DEFAULT_FILE = Path.of("..", "config", "scenarios", "default.json");

	private TestScenarios() {
	}

	/** Every provider: cost 1, latency 200 ms, available, never fails; individual providers can be overridden. */
	public static ProviderScenario neutral(Map<String, ProviderSettings> overrides) {
		Map<String, ProviderSettings> settings = new HashMap<>();
		SharedFeatureConfig.CONFIG.providerIds()
			.forEach(id -> settings.put(id, new ProviderSettings(BigDecimal.ONE, 200, true, 0.0)));
		settings.putAll(overrides);
		return new ProviderScenario("test-scenario", "test", 42, settings);
	}

	public static ProviderScenario neutral() {
		return neutral(Map.of());
	}

}
