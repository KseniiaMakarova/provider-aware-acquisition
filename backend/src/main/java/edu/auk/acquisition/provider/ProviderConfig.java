package edu.auk.acquisition.provider;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.auk.acquisition.feature.FeatureConfiguration;

@Configuration(proxyBeanMethods = false)
class ProviderConfig {

	@Bean
	ProviderRegistry providerRegistry(FeatureConfiguration config, List<InformationProvider> providers) {
		return new ProviderRegistry(config, providers);
	}

	@Bean
	ProviderExecutor providerExecutor(ProviderRegistry registry) {
		return new ProviderExecutor(registry);
	}

}
