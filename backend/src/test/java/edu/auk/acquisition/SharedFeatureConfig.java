package edu.auk.acquisition;

import java.nio.file.Path;

import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.FeatureConfigurationLoader;

/** The repository's shared feature configuration, as tests see it from the backend module. */
public final class SharedFeatureConfig {

	public static final Path PATH = Path.of("..", "config", "feature-groups.json");

	public static final FeatureConfiguration CONFIG = FeatureConfigurationLoader.load(PATH);

	private SharedFeatureConfig() {
	}

}
