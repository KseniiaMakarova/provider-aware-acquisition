package edu.auk.acquisition.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ObservedFeatures;
import edu.auk.acquisition.prediction.Prediction;
import edu.auk.acquisition.prediction.PredictionClient;
import edu.auk.acquisition.prediction.ValueEstimate;

/**
 * Shared setup for /advance tests: real HTTP, PostgreSQL and simulated providers; only the ML
 * service is replaced. The fake model returns 0.05 + 0.1 per acquired provider, so each
 * prediction shows which state it was made from.
 */
abstract class AdvanceTestSupport {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	PredictionClient predictionClient;

	@BeforeEach
	void fakeModel() {
		// doAnswer form: re-stubbing with when(...) would call the previous answer with a null argument.
		doAnswer(call -> prediction(call.getArgument(0))).when(predictionClient).predict(any());
	}

	static Prediction prediction(ObservedFeatures observed) {
		return new Prediction(0.05 + 0.1 * observed.acquiredProviders().size(), "mask-hgb-test", config.version());
	}

	/**
	 * Fake value estimates: the expected information gain of each candidate depends on the
	 * providers already acquired, as given by {@code gains}; missing entries are 0.
	 */
	void fakeEstimates(Function<Set<String>, Map<String, Double>> gains) {
		doAnswer(call -> {
			ObservedFeatures observed = call.getArgument(0);
			List<String> candidates = call.getArgument(1);
			Map<String, Double> gain = gains.apply(observed.acquiredProviders());
			return new ValueEstimate("knn-eig-v1", call.getArgument(2), "ref-test", "mask-hgb-test",
					prediction(observed).probability(), candidates.stream()
						.map(c -> new ValueEstimate.ProviderValue(c, gain.getOrDefault(c, 0.0), 0.3, 0.02))
						.toList());
		}).when(predictionClient).estimateValue(any(), any(), anyInt());
	}

	String createSession(String recordId) throws Exception {
		return createSession(recordId, "FIXED_ORDER");
	}

	String createSession(String recordId, String policyId) throws Exception {
		return createSession(recordId, policyId, null);
	}

	/** @param scenarioId scenario to use, or {@code null} for the default */
	String createSession(String recordId, String policyId, String scenarioId) throws Exception {
		String scenario = scenarioId == null ? "" : ", \"scenarioId\": \"" + scenarioId + "\"";
		String body = mvc
			.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
				.content("{\"recordId\": \"" + recordId + "\", \"policyId\": \"" + policyId + "\"" + scenario + "}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.sessionId");
	}

	ResultActions advance(String sessionId) throws Exception {
		return mvc.perform(post("/api/v1/sessions/" + sessionId + "/advance"));
	}

	String advanceBody(String sessionId) throws Exception {
		return advance(sessionId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	String getSession(String sessionId) throws Exception {
		return mvc.perform(get("/api/v1/sessions/" + sessionId))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	/** Feature names of providers that have not been acquired: none may appear before acquisition. */
	static List<String> hiddenFeatures(List<String> acquiredProviders) {
		List<String> hidden = new ArrayList<>();
		config.providers()
			.stream()
			.filter(p -> !acquiredProviders.contains(p.id()))
			.forEach(p -> hidden.addAll(p.features()));
		hidden.addAll(config.excludedColumns());
		hidden.add(config.targetColumn());
		return hidden;
	}

	int stepCount(String sessionId) {
		return jdbc.queryForObject("SELECT count(*) FROM acquisition_step WHERE session_id = CAST(? AS uuid)",
				Integer.class, sessionId);
	}

}
