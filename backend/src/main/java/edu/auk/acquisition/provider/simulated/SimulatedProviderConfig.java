package edu.auk.acquisition.provider.simulated;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.auk.acquisition.dataset.RestrictedRecordReader;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.provider.InformationProvider;
import edu.auk.acquisition.scenario.ProviderScenario;

@Configuration(proxyBeanMethods = false)
class SimulatedProviderConfig {

	/** One simulated provider per configured group. */
	@Bean
	List<InformationProvider> simulatedProviders(FeatureConfiguration config, RestrictedRecordReader reader,
			ProviderScenario scenario) {
		return config.providers()
			.stream()
			.<InformationProvider>map(group -> new SimulatedProvider(group, reader, scenario))
			.toList();
	}

}
