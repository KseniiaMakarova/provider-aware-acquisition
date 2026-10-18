package edu.auk.acquisition.scenario;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.auk.acquisition.config.AcquisitionProperties;
import edu.auk.acquisition.feature.FeatureConfiguration;

@Configuration(proxyBeanMethods = false)
class ScenarioConfig {

	private static final Logger log = LoggerFactory.getLogger(ScenarioConfig.class);

	@Bean
	ProviderScenario providerScenario(AcquisitionProperties properties, FeatureConfiguration config) {
		ProviderScenario scenario = ProviderScenario.load(properties.scenarioConfigPath(), config);
		log.info("Loaded provider scenario {} (seed {})", scenario.version(), scenario.seed());
		return scenario;
	}

}
