package edu.auk.acquisition.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import edu.auk.acquisition.feature.FeatureConfiguration;

/**
 * All predefined scenarios a session can choose from, by version, with one default. Scenarios are
 * read once at startup from the {@code *.json} files of one folder.
 */
public class ScenarioCatalog {

	private final Map<String, ProviderScenario> scenarios = new LinkedHashMap<>();

	private final ProviderScenario defaultScenario;

	/** @throws ScenarioException if two scenarios share a version or the default is missing */
	public ScenarioCatalog(Collection<ProviderScenario> scenarios, String defaultVersion) {
		List<ProviderScenario> ordered = new ArrayList<>(scenarios);
		ordered.sort(Comparator.comparing((ProviderScenario s) -> !s.version().equals(defaultVersion))
			.thenComparing(ProviderScenario::version));
		for (ProviderScenario scenario : ordered) {
			if (this.scenarios.put(scenario.version(), scenario) != null) {
				throw new ScenarioException("More than one scenario with version " + scenario.version());
			}
		}
		this.defaultScenario = this.scenarios.get(defaultVersion);
		if (defaultScenario == null) {
			throw new ScenarioException(
					"Default scenario " + defaultVersion + " is not among the scenarios " + this.scenarios.keySet());
		}
	}

	/** Loads every {@code *.json} file in the folder. */
	public static ScenarioCatalog load(Path folder, String defaultVersion, FeatureConfiguration config) {
		List<ProviderScenario> scenarios;
		try (Stream<Path> files = Files.list(folder)) {
			scenarios = files.filter(f -> f.getFileName().toString().endsWith(".json"))
				.sorted()
				.map(f -> ProviderScenario.load(f, config))
				.toList();
		}
		catch (IOException e) {
			throw new ScenarioException("Cannot list scenarios in " + folder.toAbsolutePath(), e);
		}
		return new ScenarioCatalog(scenarios, defaultVersion);
	}

	public ProviderScenario defaultScenario() {
		return defaultScenario;
	}

	public Optional<ProviderScenario> find(String version) {
		return Optional.ofNullable(scenarios.get(version));
	}

	/** All scenarios, the default first, then by version. */
	public List<ProviderScenario> all() {
		return List.copyOf(scenarios.values());
	}

	/**
	 * The scenario a stored session runs with: its own snapshot, or, for sessions stored before
	 * snapshots were kept, the current scenario with the same version.
	 */
	public ProviderScenario forSession(String version, ProviderScenario snapshot) {
		if (snapshot != null) {
			return snapshot;
		}
		return find(version).orElseThrow(
				() -> new ScenarioException("Session scenario " + version + " has no snapshot and is no longer configured"));
	}

}
