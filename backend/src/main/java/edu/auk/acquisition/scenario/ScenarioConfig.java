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
	ScenarioCatalog scenarioCatalog(AcquisitionProperties properties, FeatureConfiguration config) {
		ScenarioCatalog catalog = ScenarioCatalog.load(properties.scenariosDirPath(), properties.defaultScenario(), config);
		catalog.all().forEach(s -> log.info("Loaded provider scenario {} (seed {}){}", s.version(), s.seed(),
				s == catalog.defaultScenario() ? ", the default" : ""));
		return catalog;
	}

}
