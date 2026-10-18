package edu.auk.acquisition.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.TestDataset;
import edu.auk.acquisition.TestcontainersConfiguration;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ObservedFeatures;
import edu.auk.acquisition.prediction.Prediction;
import edu.auk.acquisition.prediction.PredictionClient;
import edu.auk.acquisition.prediction.PredictionRejectedException;
import edu.auk.acquisition.prediction.PredictionUnavailableException;

/** Slice 1 through the real HTTP layer and PostgreSQL; only the ML service is replaced. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = TestDataset.FEATURES_DIR_PROPERTY)
@AutoConfigureMockMvc
class SessionApiTest {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	PredictionClient predictionClient;

	@BeforeEach
	void mlServiceAnswers() {
		when(predictionClient.predict(any())).thenReturn(new Prediction(0.37, "mask-hgb-test", config.version()));
	}

	ResultActions createSession(String body) throws Exception {
		return mvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	int sessionCount() {
		return jdbc.queryForObject("SELECT count(*) FROM acquisition_session", Integer.class);
	}

	/** Every name that must not appear before its provider is acquired, plus labels and demographics. */
	static List<String> forbiddenNames() {
		List<String> names = new ArrayList<>();
		config.providers().forEach(p -> names.addAll(p.features()));
		names.addAll(config.excludedColumns());
		names.add(config.targetColumn());
		return names;
	}

	// --- Creating a session ---

	@Test
	void createsASessionFromTheInitialInformationOnly() throws Exception {
		String body = createSession("""
				{"recordId": "101", "policyId": "FIXED_ORDER"}
				""").andExpect(status().isCreated())
			.andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/sessions/")))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.datasetSplit").value("validation"))
			.andExpect(jsonPath("$.scenarioVersion").value("default-v1"))
			.andExpect(jsonPath("$.featureConfigVersion").value(config.version()))
			.andExpect(jsonPath("$.observedFeatures.LIMIT_BAL").value(1001.0))
			.andExpect(jsonPath("$.observedFeatures.BILL_AMT1").value(1002.0))
			.andExpect(jsonPath("$.observedFeatures.PAY_AMT1").value(1003.0))
			.andExpect(jsonPath("$.observedFeatures.length()").value(3))
			.andExpect(jsonPath("$.observedMask").value(org.hamcrest.Matchers.contains("LIMIT_BAL", "BILL_AMT1", "PAY_AMT1")))
			.andExpect(jsonPath("$.acquiredProviders").isEmpty())
			.andExpect(jsonPath("$.remainingProviders").value(
					org.hamcrest.Matchers.contains("REPAYMENT_HISTORY", "BILLING_HISTORY", "PAYMENT_HISTORY")))
			.andExpect(jsonPath("$.latestPrediction.sequenceNumber").value(0))
			.andExpect(jsonPath("$.latestPrediction.probability").value(0.37))
			.andExpect(jsonPath("$.predictions.length()").value(1))
			.andExpect(jsonPath("$.steps").isEmpty())
			.andExpect(jsonPath("$.totalCost").value(0))
			.andExpect(jsonPath("$.accumulatedSimulatedMs").value(0))
			.andReturn()
			.getResponse()
			.getContentAsString();

		forbiddenNames().forEach(name -> assertThat(body).doesNotContain("\"" + name + "\""));
	}

	@Test
	void theMlServiceReceivesOnlyTheInitialFeatures() throws Exception {
		createSession("""
				{"recordId": "101", "policyId": "FIXED_ORDER"}
				""").andExpect(status().isCreated());

		ArgumentCaptor<ObservedFeatures> sent = ArgumentCaptor.forClass(ObservedFeatures.class);
		verify(predictionClient).predict(sent.capture());
		assertThat(sent.getValue().mask()).isEqualTo(config.initialFeatures());
		assertThat(sent.getValue().values()).hasSize(config.initialFeatures().size());
	}

	@Test
	void theDatabaseHoldsOnlyObservedValues() throws Exception {
		String location = createSession("""
				{"recordId": "101", "policyId": "FIXED_ORDER"}
				""").andReturn().getResponse().getHeader("Location");
		String id = location.substring(location.lastIndexOf('/') + 1);

		String stored = jdbc.queryForObject(
				"SELECT observed_features::text FROM acquisition_session WHERE id = CAST(? AS uuid)", String.class, id);
		forbiddenNames().forEach(name -> assertThat(stored).doesNotContain("\"" + name + "\""));
	}

	@Test
	void sessionCanBeReadBack() throws Exception {
		String location = createSession("""
				{"recordId": "102", "policyId": "FIXED_ORDER"}
				""").andReturn().getResponse().getHeader("Location");

		mvc.perform(get(location))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.recordId").value("102"))
			.andExpect(jsonPath("$.observedFeatures.BILL_AMT1").value(0.0))
			.andExpect(jsonPath("$.latestPrediction.probability").value(0.37))
			.andExpect(jsonPath("$.predictions[0].observedMask.length()").value(3));
	}

	// --- Errors: nothing is stored ---

	@Test
	void recordOutsideTheServedSplitIsNotFound() throws Exception {
		int before = sessionCount();
		createSession("""
				{"recordId": "201", "policyId": "FIXED_ORDER"}
				""").andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("RECORD_NOT_AVAILABLE"));
		assertThat(sessionCount()).isEqualTo(before);
		verify(predictionClient, never()).predict(any());
	}

	@Test
	void mlOutageReturns503AndStoresNothing() throws Exception {
		when(predictionClient.predict(any())).thenThrow(new PredictionUnavailableException("connection refused"));
		int before = sessionCount();

		createSession("""
				{"recordId": "101", "policyId": "FIXED_ORDER"}
				""").andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error").value("ML_SERVICE_UNAVAILABLE"));
		assertThat(sessionCount()).isEqualTo(before);
	}

	@Test
	void mlRejectionReturns502() throws Exception {
		when(predictionClient.predict(any())).thenThrow(new PredictionRejectedException("INVALID_OBSERVATION", "x"));
		createSession("""
				{"recordId": "101", "policyId": "FIXED_ORDER"}
				""").andExpect(status().isBadGateway()).andExpect(jsonPath("$.error").value("ML_SERVICE_REJECTED"));
	}

	@Test
	void unsupportedPolicyIsABadRequest() throws Exception {
		createSession("""
				{"recordId": "101", "policyId": "ORACLE"}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("UNSUPPORTED_POLICY"));
	}

	@Test
	void missingFieldsAreABadRequest() throws Exception {
		createSession("""
				{"policyId": "FIXED_ORDER"}
				""").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("recordId")));
	}

	@Test
	void unknownFieldsAreRejected() throws Exception {
		createSession("""
				{"recordId": "101", "policyId": "FIXED_ORDER", "label": 1}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
	}

	@Test
	void malformedJsonIsABadRequest() throws Exception {
		createSession("{not json").andExpect(status().isBadRequest());
	}

	@Test
	void unknownSessionIsNotFound() throws Exception {
		mvc.perform(get("/api/v1/sessions/00000000-0000-0000-0000-000000000000"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("SESSION_NOT_FOUND"));
	}

	@Test
	void malformedSessionIdIsABadRequest() throws Exception {
		mvc.perform(get("/api/v1/sessions/not-a-uuid")).andExpect(status().isBadRequest());
	}

	// --- Providers ---

	@Test
	void providersAreListedWithScenarioCharacteristicsAndNoValues() throws Exception {
		mvc.perform(get("/api/v1/providers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scenarioVersion").value("default-v1"))
			.andExpect(jsonPath("$.providers.length()").value(3))
			.andExpect(jsonPath("$.providers[0].id").value("REPAYMENT_HISTORY"))
			.andExpect(jsonPath("$.providers[0].suppliedFeatures[0]").value("PAY_0"))
			.andExpect(jsonPath("$.providers[0].cost").value(1.0))
			.andExpect(jsonPath("$.providers[0].expectedLatencyMs").value(200))
			.andExpect(jsonPath("$.providers[0].available").value(true))
			.andExpect(jsonPath("$.providers[0].values").doesNotExist());
	}

}
