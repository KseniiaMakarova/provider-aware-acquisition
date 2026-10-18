package edu.auk.acquisition.feature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.auk.acquisition.config.AcquisitionProperties;

@Configuration(proxyBeanMethods = false)
class FeatureConfigurationConfig {

	private static final Logger log = LoggerFactory.getLogger(FeatureConfigurationConfig.class);

	/** Loaded once at startup; an invalid file stops the application. */
	@Bean
	FeatureConfiguration featureConfiguration(AcquisitionProperties properties) {
		FeatureConfiguration config = FeatureConfigurationLoader.load(properties.featureConfig());
		log.info("Loaded feature configuration {}: {} initial features, providers {}", config.version(),
				config.initialFeatures().size(), config.providerIds());
		return config;
	}

}
