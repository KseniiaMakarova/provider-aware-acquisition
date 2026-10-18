package edu.auk.acquisition.prediction;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import edu.auk.acquisition.config.AcquisitionProperties;
import edu.auk.acquisition.feature.FeatureConfiguration;

@Configuration(proxyBeanMethods = false)
class PredictionClientConfig {

	@Bean
	PredictionClient predictionClient(AcquisitionProperties properties, FeatureConfiguration featureConfiguration) {
		return new HttpPredictionClient(mlServiceRestClient(properties.mlService()), featureConfiguration);
	}

	static RestClient mlServiceRestClient(AcquisitionProperties.MlService settings) {
		HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(settings.connectTimeout())
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(settings.readTimeout());
		return RestClient.builder().baseUrl(settings.baseUrl().toString()).requestFactory(requestFactory).build();
	}

}
