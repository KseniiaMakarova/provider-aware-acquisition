package edu.auk.acquisition.prediction;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ObservedFeatures;

/** Calls {@code POST /internal/v1/predict} of the FastAPI service. */
public class HttpPredictionClient implements PredictionClient {

	static final String PREDICT_PATH = "/internal/v1/predict";

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final RestClient restClient;
	private final FeatureConfiguration featureConfiguration;

	public HttpPredictionClient(RestClient restClient, FeatureConfiguration featureConfiguration) {
		this.restClient = restClient;
		this.featureConfiguration = featureConfiguration;
	}

	@Override
	public Prediction predict(ObservedFeatures observed) {
		PredictResponse response;
		try {
			response = restClient.post()
				.uri(PREDICT_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.body(new PredictRequest(observed.values(), observed.mask()))
				.retrieve()
				.onStatus(status -> status.isSameCodeAs(HttpStatus.UNPROCESSABLE_CONTENT), (request, res) -> {
					ErrorBody error = readError(res);
					throw new PredictionRejectedException(error.error(), error.message());
				})
				.onStatus(HttpStatusCode::isError, (request, res) -> {
					throw new PredictionUnavailableException("ML service returned HTTP " + res.getStatusCode().value());
				})
				.body(PredictResponse.class);
		}
		catch (PredictionException e) {
			throw e;
		}
		catch (RestClientException e) {
			throw new PredictionUnavailableException("ML service request failed: " + e.getMessage(), e);
		}
		return validated(response);
	}

	private Prediction validated(PredictResponse response) {
		if (response == null || response.probability() == null || response.modelVersion() == null) {
			throw new PredictionUnavailableException("ML service returned an incomplete response: " + response);
		}
		double probability = response.probability();
		if (!(probability >= 0.0 && probability <= 1.0)) {
			throw new PredictionUnavailableException("ML service returned a probability outside [0, 1]: " + probability);
		}
		if (!featureConfiguration.version().equals(response.featureConfigVersion())) {
			throw new PredictionUnavailableException("ML service uses feature configuration "
					+ response.featureConfigVersion() + ", backend uses " + featureConfiguration.version());
		}
		return new Prediction(probability, response.modelVersion(), response.featureConfigVersion());
	}

	private static ErrorBody readError(ClientHttpResponse response) throws IOException {
		try {
			ErrorBody body = MAPPER.readValue(response.getBody(), ErrorBody.class);
			return new ErrorBody(body.error() == null ? "UNKNOWN" : body.error(), body.message());
		}
		catch (JacksonException e) {
			return new ErrorBody("UNKNOWN", "unreadable error response");
		}
	}

	record PredictRequest(Map<String, Double> observedFeatures, List<String> observedMask) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record PredictResponse(Double probability, String modelVersion, String featureConfigVersion) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ErrorBody(String error, String message) {
	}

}
