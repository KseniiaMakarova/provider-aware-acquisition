package edu.auk.acquisition.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.TestDataset;
import edu.auk.acquisition.dataset.DatasetAccessMode;
import edu.auk.acquisition.dataset.RestrictedRecordReader;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ObservedFeatures;

class InitialInformationLoaderTest {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	final InitialInformationLoader loader = new InitialInformationLoader(
			RestrictedRecordReader.load(TestDataset.FEATURES_DIR, DatasetAccessMode.DEVELOPMENT, config), config);

	@Test
	void sessionStartsWithOnlyTheInitialFeaturesObserved() {
		ObservedFeatures observed = loader.load("101");

		assertThat(observed.mask()).isEqualTo(config.initialFeatures());
		assertThat(observed.acquiredProviders()).isEmpty();
		assertThat(observed.values()).doesNotContainKeys(
				config.providers().stream().flatMap(p -> p.features().stream()).toArray(String[]::new));
	}

}
