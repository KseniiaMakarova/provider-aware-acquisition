package edu.auk.acquisition.policy;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.auk.acquisition.config.AcquisitionProperties;
import edu.auk.acquisition.feature.FeatureConfiguration;

@Configuration(proxyBeanMethods = false)
class PolicyConfig {

	private static final Logger log = LoggerFactory.getLogger(PolicyConfig.class);

	/**
	 * The fixed-order baseline (configuration order), the full-acquisition reference and the
	 * adaptive policy with its versioned settings.
	 */
	@Bean
	PolicyRegistry policyRegistry(FeatureConfiguration config, AcquisitionProperties properties) {
		AdaptivePolicySettings adaptive = AdaptivePolicySettings.load(properties.adaptivePolicyConfigPath());
		log.info("Loaded adaptive policy settings {}: K={}, cost weight {}, latency weight {}", adaptive.version(),
				adaptive.sampleSize(), adaptive.costWeightBitsPerUnit(), adaptive.latencyWeightBitsPerSecond());
		return new PolicyRegistry(List.of(new AdaptivePolicy(adaptive), new FixedOrderPolicy(config.providerIds()),
				new FullAcquisitionPolicy()));
	}

}
