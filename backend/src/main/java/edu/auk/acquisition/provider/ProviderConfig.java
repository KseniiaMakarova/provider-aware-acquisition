package edu.auk.acquisition.provider;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.scenario.ProviderScenario;

@Configuration(proxyBeanMethods = false)
class ProviderConfig {

	private static final Logger log = LoggerFactory.getLogger(ProviderConfig.class);

	@Bean
	ProviderRegistry providerRegistry(FeatureConfiguration config, ProviderScenario scenario,
			List<InformationProvider> providers) {
		ProviderRegistry registry = new ProviderRegistry(config, scenario, providers);
		registry.metadata()
			.forEach(m -> log.info("Provider {}: cost {}, latency {} ms, available {}", m.id(), m.cost(),
					m.expectedLatencyMs(), m.available()));
		return registry;
	}

	@Bean
	ProviderExecutor providerExecutor(ProviderRegistry registry) {
		return new ProviderExecutor(registry);
	}

}
