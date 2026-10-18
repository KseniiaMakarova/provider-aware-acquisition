package edu.auk.acquisition.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

class HttpPredictionClientTest {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	static final String URL = "http://ml-service/internal/v1/predict";

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

	static String response(double probability, String featureConfigVersion) {
		return """
				{"probability": %s, "modelVersion": "mask-hgb-test", "featureConfigVersion": "%s"}
				""".formatted(probability, featureConfigVersion);
	}

	@Test
	void sendsExactlyTheObservedFeaturesAndMask() {
		server.expect(requestTo(URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(content().json("""
					{
					  "observedFeatures": {"LIMIT_BAL": 50000.0, "BILL_AMT1": 0.0, "PAY_AMT1": 2000.0},
					  "observedMask": ["LIMIT_BAL", "BILL_AMT1", "PAY_AMT1"]
					}
					""", JsonCompareMode.STRICT))
			.andRespond(withSuccess(response(0.25, config.version()), MediaType.APPLICATION_JSON));

		Prediction prediction = client.predict(initialState());

		assertThat(prediction).isEqualTo(new Prediction(0.25, "mask-hgb-test", config.version()));
		server.verify();
	}

	@Test
	void rejectionFromTheServiceKeepsItsErrorCodeAndMessage() {
		server.expect(requestTo(URL))
			.andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON)
				.body("""
						{"error": "INVALID_OBSERVATION", "message": "Partial group for REPAYMENT_HISTORY"}
						"""));

		assertThatThrownBy(() -> client.predict(initialState())).isInstanceOfSatisfying(PredictionRejectedException.class,
				e -> {
					assertThat(e.errorCode()).isEqualTo("INVALID_OBSERVATION");
					assertThat(e.getMessage()).contains("Partial group for REPAYMENT_HISTORY");
				});
	}

	@Test
	void unreadableRejectionBodyIsStillAReject() {
		server.expect(requestTo(URL))
			.andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.TEXT_PLAIN).body("nope"));

		assertThatThrownBy(() -> client.predict(initialState())).isInstanceOfSatisfying(PredictionRejectedException.class,
				e -> assertThat(e.errorCode()).isEqualTo("UNKNOWN"));
	}

	@Test
	void serverErrorMeansUnavailable() {
		server.expect(requestTo(URL)).andRespond(withServerError());

		assertThatThrownBy(() -> client.predict(initialState())).isInstanceOf(PredictionUnavailableException.class)
			.hasMessageContaining("HTTP 500");
	}

	@Test
	void probabilityOutsideTheUnitIntervalIsRefused() {
		server.expect(requestTo(URL)).andRespond(withSuccess(response(1.5, config.version()), MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.predict(initialState())).isInstanceOf(PredictionUnavailableException.class)
			.hasMessageContaining("outside [0, 1]");
	}

	@Test
	void modelForAnotherFeatureConfigurationIsRefused() {
		server.expect(requestTo(URL)).andRespond(withSuccess(response(0.3, "fg-1"), MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.predict(initialState())).isInstanceOf(PredictionUnavailableException.class)
			.hasMessageContaining("feature configuration fg-1");
	}

	@Test
	void incompleteResponseIsRefused() {
		server.expect(requestTo(URL)).andRespond(withSuccess("{\"probability\": 0.3}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.predict(initialState())).isInstanceOf(PredictionUnavailableException.class)
			.hasMessageContaining("incomplete");
	}

	@Test
	void malformedResponseIsRefused() {
		server.expect(requestTo(URL)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.predict(initialState())).isInstanceOf(PredictionUnavailableException.class);
	}

}
