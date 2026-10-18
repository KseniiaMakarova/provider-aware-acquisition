package edu.auk.acquisition.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.config.AcquisitionProperties;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ObservedFeatures;

/**
 * Contract check against a running FastAPI service and the prepared validation data.
 *
 * <p>Runs only when ML_SERVICE_URL is set, e.g. {@code ML_SERVICE_URL=http://localhost:8000 ./mvnw test}.
 */
@EnabledIfEnvironmentVariable(named = "ML_SERVICE_URL", matches = ".+")
class MlServiceContractTest {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	static RestClient restClient;

	static PredictionClient client;

	static Map<String, Double> record;

	@BeforeAll
	static void setUp() throws IOException {
		var settings = new AcquisitionProperties.MlService(URI.create(System.getenv("ML_SERVICE_URL")),
				Duration.ofSeconds(1), Duration.ofSeconds(3));
		restClient = PredictionClientConfig.mlServiceRestClient(settings);
		client = new HttpPredictionClient(restClient, config);
		record = firstValidationRecord();
	}

	static Map<String, Double> firstValidationRecord() throws IOException {
		List<String> lines = Files.readAllLines(Path.of("..", "data", "features", "validation.csv"));
		String[] header = lines.get(0).split(",");
		String[] values = lines.get(1).split(",");
		Map<String, Double> row = new HashMap<>();
		for (int i = 0; i < header.length; i++) {
			row.put(header[i], Double.valueOf(values[i]));
		}
		return row;
	}

	@Test
	void everyLegalStateIsPredictedWithTheSameModelAndConfiguration() {
		for (Set<String> state : config.observationStates()) {
			Map<String, Double> values = new HashMap<>();
			config.observedFeatures(state).forEach(f -> values.put(f, record.get(f)));

			Prediction prediction = client.predict(ObservedFeatures.of(values, config));

			assertThat(prediction.probability()).isBetween(0.0, 1.0);
			assertThat(prediction.featureConfigVersion()).isEqualTo(config.version());
			assertThat(prediction.modelVersion()).contains(config.version());
		}
	}

	@Test
	void serviceRejectsAPartialGroupWithTheAgreedErrorFormat() {
		Map<String, Object> body = Map.of("observedFeatures", Map.of("LIMIT_BAL", 1.0, "BILL_AMT1", 1.0, "PAY_AMT1", 1.0,
				"PAY_2", 0.0), "observedMask", List.of("LIMIT_BAL", "BILL_AMT1", "PAY_AMT1", "PAY_2"));

		assertThatThrownBy(() -> restClient.post()
			.uri(HttpPredictionClient.PREDICT_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.body(body)
			.retrieve()
			.toBodilessEntity()).isInstanceOfSatisfying(HttpClientErrorException.class, e -> {
				assertThat(e.getStatusCode().isSameCodeAs(HttpStatus.UNPROCESSABLE_CONTENT)).isTrue();
				assertThat(e.getResponseBodyAsString()).contains("\"error\":\"INVALID_OBSERVATION\"");
			});
	}

}
