package edu.auk.acquisition.policy;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.auk.acquisition.feature.FeatureConfiguration;

@Configuration(proxyBeanMethods = false)
class PolicyConfig {

	/** Alpha: the fixed-order baseline, querying providers in feature-configuration order. */
	@Bean
	PolicyRegistry policyRegistry(FeatureConfiguration config) {
		return new PolicyRegistry(List.of(new FixedOrderPolicy(config.providerIds())));
	}

}
