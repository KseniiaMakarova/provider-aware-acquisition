package edu.auk.acquisition.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ObservedFeatures;

/** {@code POST /internal/v1/acquisition-value} through {@link HttpPredictionClient}. */
class HttpValueEstimateClientTest {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	static final String URL = "http://ml-service/internal/v1/acquisition-value";

	static final List<String> CANDIDATES = List.of("REPAYMENT_HISTORY", "PAYMENT_HISTORY");

	MockRestServiceServer server;

	HttpPredictionClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://ml-service");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new HttpPredictionClient(builder.build(), config);
	}

	static ObservedFeatures initialState() {
		return ObservedFeatures.of(Map.of("PAY_AMT1", 2000, "LIMIT_BAL", 50000, "BILL_AMT1", 0), config);
	}

	static String response(String featureConfig, String estimates) {
		return """
				{"method": "knn-eig-v1", "sampleSize": 100, "referenceVersion": "ref-fg-2-test",
				 "modelVersion": "mask-hgb-test", "featureConfigVersion": "%s", "currentProbability": 0.25,
				 "estimates": [%s]}
				""".formatted(featureConfig, estimates);
	}

	static String estimate(String provider, double gain, double probability) {
		return """
				{"providerId": "%s", "expectedInformationGainBits": %s, "expectedProbability": %s,
				 "expectedAbsoluteChange": 0.05}
				""".formatted(provider, gain, probability);
	}

	static String valid() {
		return response(config.version(),
				estimate("REPAYMENT_HISTORY", 0.09, 0.24) + "," + estimate("PAYMENT_HISTORY", 0.019, 0.26));
	}

	@Test
	void sendsOnlyTheObservedStateCandidatesAndSampleSize() {
		server.expect(requestTo(URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().json("""
					{
					  "observedFeatures": {"LIMIT_BAL": 50000.0, "BILL_AMT1": 0.0, "PAY_AMT1": 2000.0},
					  "observedMask": ["LIMIT_BAL", "BILL_AMT1", "PAY_AMT1"],
					  "candidateProviders": ["REPAYMENT_HISTORY", "PAYMENT_HISTORY"],
					  "sampleSize": 100
					}
					""", JsonCompareMode.STRICT))
			.andRespond(withSuccess(valid(), MediaType.APPLICATION_JSON));

		ValueEstimate estimate = client.estimateValue(initialState(), CANDIDATES, 100);

		assertThat(estimate).isEqualTo(new ValueEstimate("knn-eig-v1", 100, "ref-fg-2-test", "mask-hgb-test", 0.25,
				List.of(new ValueEstimate.ProviderValue("REPAYMENT_HISTORY", 0.09, 0.24, 0.05),
						new ValueEstimate.ProviderValue("PAYMENT_HISTORY", 0.019, 0.26, 0.05))));
		server.verify();
	}

	@Test
	void estimatesForOtherProvidersAreRefused() {
		server.expect(requestTo(URL))
			.andRespond(withSuccess(response(config.version(), estimate("REPAYMENT_HISTORY", 0.09, 0.2)),
					MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.estimateValue(initialState(), CANDIDATES, 100))
			.isInstanceOf(PredictionUnavailableException.class)
			.hasMessageContaining("instead of the candidates");
	}

	@Test
	void negativeInformationGainIsRefused() {
		server.expect(requestTo(URL))
			.andRespond(withSuccess(response(config.version(),
					estimate("REPAYMENT_HISTORY", -0.1, 0.2) + "," + estimate("PAYMENT_HISTORY", 0.01, 0.2)),
					MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.estimateValue(initialState(), CANDIDATES, 100))
			.isInstanceOf(PredictionUnavailableException.class)
			.hasMessageContaining("information gain");
	}

	@Test
	void estimateForAnotherFeatureConfigurationIsRefused() {
		server.expect(requestTo(URL)).andRespond(withSuccess(response("fg-1", ""), MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.estimateValue(initialState(), CANDIDATES, 100))
			.isInstanceOf(PredictionUnavailableException.class)
			.hasMessageContaining("feature configuration fg-1");
	}

	@Test
	void incompleteEstimateIsRefused() {
		server.expect(requestTo(URL))
			.andRespond(withSuccess("{\"method\": \"knn-eig-v1\"}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.estimateValue(initialState(), CANDIDATES, 100))
			.isInstanceOf(PredictionUnavailableException.class)
			.hasMessageContaining("incomplete");
	}

	@Test
	void rejectionKeepsTheServiceErrorCode() {
		server.expect(requestTo(URL))
			.andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON)
				.body("""
						{"error": "INVALID_OBSERVATION", "message": "Candidate providers are already acquired"}
						"""));

		assertThatThrownBy(() -> client.estimateValue(initialState(), CANDIDATES, 100))
			.isInstanceOfSatisfying(PredictionRejectedException.class,
					e -> assertThat(e.getMessage()).contains("INVALID_OBSERVATION", "already acquired"));
	}

	@Test
	void missingValueReferenceMakesEstimationUnavailable() {
		server.expect(requestTo(URL))
			.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\": \"VALUE_REFERENCE_UNAVAILABLE\", \"message\": \"No value reference\"}"));

		assertThatThrownBy(() -> client.estimateValue(initialState(), CANDIDATES, 100))
			.isInstanceOf(PredictionUnavailableException.class)
			.hasMessageContaining("503");
	}

}
