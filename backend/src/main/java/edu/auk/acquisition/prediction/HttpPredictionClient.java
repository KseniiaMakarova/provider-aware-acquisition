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

/** Calls {@code POST /internal/v1/predict} and {@code POST /internal/v1/acquisition-value} of the FastAPI service. */
public class HttpPredictionClient implements PredictionClient {

	static final String PREDICT_PATH = "/internal/v1/predict";

	static final String VALUE_PATH = "/internal/v1/acquisition-value";

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final RestClient restClient;

	private final FeatureConfiguration featureConfiguration;

	public HttpPredictionClient(RestClient restClient, FeatureConfiguration featureConfiguration) {
		this.restClient = restClient;
		this.featureConfiguration = featureConfiguration;
	}

	@Override
	public Prediction predict(ObservedFeatures observed) {
		PredictResponse response = post(PREDICT_PATH, new PredictRequest(observed.values(), observed.mask()),
				PredictResponse.class);
		if (response == null || response.probability() == null || response.modelVersion() == null) {
			throw new PredictionUnavailableException("ML service returned an incomplete response: " + response);
		}
		checkProbability(response.probability());
		checkFeatureConfig(response.featureConfigVersion());
		return new Prediction(response.probability(), response.modelVersion(), response.featureConfigVersion());
	}

	@Override
	public ValueEstimate estimateValue(ObservedFeatures observed, List<String> candidateProviders, int sampleSize) {
		ValueResponse response = post(VALUE_PATH,
				new ValueRequest(observed.values(), observed.mask(), candidateProviders, sampleSize), ValueResponse.class);
		if (response == null || response.method() == null || response.sampleSize() == null
				|| response.referenceVersion() == null || response.modelVersion() == null
				|| response.currentProbability() == null || response.estimates() == null) {
			throw new PredictionUnavailableException("ML service returned an incomplete value estimate: " + response);
		}
		checkFeatureConfig(response.featureConfigVersion());
		checkProbability(response.currentProbability());
		List<String> returned = response.estimates().stream().map(ProviderValueResponse::providerId).toList();
		if (!returned.equals(candidateProviders)) {
			throw new PredictionUnavailableException(
					"ML service estimated " + returned + " instead of the candidates " + candidateProviders);
		}
		List<ValueEstimate.ProviderValue> values = response.estimates().stream().map(e -> {
			if (e.expectedInformationGainBits() == null || e.expectedProbability() == null
					|| e.expectedAbsoluteChange() == null) {
				throw new PredictionUnavailableException("ML service returned an incomplete estimate: " + e);
			}
			double gain = e.expectedInformationGainBits();
			if (!(gain >= 0 && Double.isFinite(gain))) {
				throw new PredictionUnavailableException("ML service returned an invalid information gain: " + gain);
			}
			checkProbability(e.expectedProbability());
			return new ValueEstimate.ProviderValue(e.providerId(), gain, e.expectedProbability(),
					e.expectedAbsoluteChange());
		}).toList();
		return new ValueEstimate(response.method(), response.sampleSize(), response.referenceVersion(),
				response.modelVersion(), response.currentProbability(), values);
	}

	private <T> T post(String path, Object body, Class<T> type) {
		try {
			return restClient.post()
				.uri(path)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.onStatus(status -> status.isSameCodeAs(HttpStatus.UNPROCESSABLE_CONTENT), (request, res) -> {
					ErrorBody error = readError(res);
					throw new PredictionRejectedException(error.error(), error.message());
				})
				.onStatus(HttpStatusCode::isError, (request, res) -> {
					throw new PredictionUnavailableException("ML service returned HTTP " + res.getStatusCode().value()
							+ " for " + path);
				})
				.body(type);
		}
		catch (PredictionException e) {
			throw e;
		}
		catch (RestClientException e) {
			throw new PredictionUnavailableException("ML service request failed: " + e.getMessage(), e);
		}
	}

	private static void checkProbability(double probability) {
		if (!(probability >= 0.0 && probability <= 1.0)) {
			throw new PredictionUnavailableException("ML service returned a probability outside [0, 1]: " + probability);
		}
	}

	private void checkFeatureConfig(String version) {
		if (!featureConfiguration.version().equals(version)) {
			throw new PredictionUnavailableException("ML service uses feature configuration " + version
					+ ", backend uses " + featureConfiguration.version());
		}
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

	record ValueRequest(Map<String, Double> observedFeatures, List<String> observedMask,
			List<String> candidateProviders, int sampleSize) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ValueResponse(String method, Integer sampleSize, String referenceVersion, String modelVersion,
			String featureConfigVersion, Double currentProbability, List<ProviderValueResponse> estimates) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ProviderValueResponse(String providerId, Double expectedInformationGainBits, Double expectedProbability,
			Double expectedAbsoluteChange) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ErrorBody(String error, String message) {
	}

}
