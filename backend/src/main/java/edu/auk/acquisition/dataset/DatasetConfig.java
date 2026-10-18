package edu.auk.acquisition.dataset;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import edu.auk.acquisition.feature.FeatureConfiguration;

@Configuration(proxyBeanMethods = false)
class DatasetConfig {

	private static final Logger log = LoggerFactory.getLogger(DatasetConfig.class);

	@Bean
	RestrictedRecordReader restrictedRecordReader(DatasetProperties dataset, FeatureConfiguration config,
			Environment environment) {
		checkAccessMode(dataset.accessMode(), environment.getActiveProfiles());
		RestrictedRecordReader reader = RestrictedRecordReader.load(dataset.featuresDirPath(), dataset.accessMode(),
				config);
		log.info("Serving {} records of the {} split (access mode {})", reader.size(), reader.split(),
				reader.accessMode());
		return reader;
	}

	/**
	 * Test records are served only when FINAL_EVALUATION is requested together with the
	 * final-evaluation profile, so a single changed value cannot expose them during development.
	 */
	static void checkAccessMode(DatasetAccessMode mode, String[] activeProfiles) {
		boolean finalProfile = Arrays.asList(activeProfiles).contains(DatasetAccessMode.FINAL_EVALUATION_PROFILE);
		if (mode == DatasetAccessMode.FINAL_EVALUATION && !finalProfile) {
			throw new IllegalStateException("Access mode FINAL_EVALUATION requires the '"
					+ DatasetAccessMode.FINAL_EVALUATION_PROFILE + "' Spring profile");
		}
		if (mode != DatasetAccessMode.FINAL_EVALUATION && finalProfile) {
			throw new IllegalStateException("The '" + DatasetAccessMode.FINAL_EVALUATION_PROFILE
					+ "' profile requires access mode FINAL_EVALUATION, but " + mode + " is configured");
		}
	}

}
