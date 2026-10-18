package edu.auk.acquisition.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

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

	String createSession(String recordId) throws Exception {
		String body = mvc
			.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
				.content("{\"recordId\": \"" + recordId + "\", \"policyId\": \"FIXED_ORDER\"}"))
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
