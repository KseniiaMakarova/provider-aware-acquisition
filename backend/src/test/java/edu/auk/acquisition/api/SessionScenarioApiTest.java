package edu.auk.acquisition.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import edu.auk.acquisition.TestDataset;
import edu.auk.acquisition.TestcontainersConfiguration;

/**
 * Scenarios chosen per session, using the predefined scenario files in {@code config/scenarios}.
 * Value estimates are faked with typical validation values.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = TestDataset.FEATURES_DIR_PROPERTY)
@AutoConfigureMockMvc
class SessionScenarioApiTest extends AdvanceTestSupport {

	static Map<String, Double> typical(Set<String> acquired) {
		return acquired.isEmpty()
				? Map.of("REPAYMENT_HISTORY", 0.09, "BILLING_HISTORY", 0.007, "PAYMENT_HISTORY", 0.019)
				: Map.of("REPAYMENT_HISTORY", 0.09, "BILLING_HISTORY", 0.002, "PAYMENT_HISTORY", 0.004);
	}

	@Test
	void scenariosEndpointListsThePredefinedScenarios() throws Exception {
		mvc.perform(get("/api/v1/scenarios"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.defaultScenario").value("default-v1"))
			.andExpect(jsonPath("$.scenarios[*].id").value(
					contains("default-v1", "costly-repayment-v1", "repayment-unavailable-v1", "unreliable-v1")))
			.andExpect(jsonPath("$.scenarios[1].providers[0].cost").value(10))
			.andExpect(jsonPath("$.scenarios[2].providers[0].available").value(false))
			.andExpect(jsonPath("$.scenarios[3].providers[0].timeoutMs").value(1000))
			.andExpect(jsonPath("$.scenarios[3].providers[0].latencyJitterMs").value(800));
	}

	@Test
	void eachSessionUsesItsOwnScenarioInTheSameBackend() throws Exception {
		fakeEstimates(SessionScenarioApiTest::typical);
		String neutral = createSession("101", "ADAPTIVE");
		String costly = createSession("101", "ADAPTIVE", "costly-repayment-v1");

		assertThat((String) JsonPath.read(getSession(neutral), "$.scenarioVersion")).isEqualTo("default-v1");
		String costlyState = getSession(costly);
		assertThat((String) JsonPath.read(costlyState, "$.scenarioVersion")).isEqualTo("costly-repayment-v1");
		assertThat(((Number) JsonPath.read(costlyState, "$.providers[0].cost")).doubleValue()).isEqualTo(10.0);

		// Neutral: Repayment is worth it. Repayment at cost 10 is not (price 0.101 > 0.09): Payment instead.
		assertThat((String) JsonPath.read(advanceBody(neutral), "$.step.providerId")).isEqualTo("REPAYMENT_HISTORY");
		String body = advanceBody(costly);
		assertThat((String) JsonPath.read(body, "$.step.providerId")).isEqualTo("PAYMENT_HISTORY");
		assertThat(((Number) JsonPath.read(body, "$.decision.candidates[0].score")).doubleValue())
			.isCloseTo(0.09 - 0.101, within(1e-9));
		assertThat(((Number) JsonPath.read(body, "$.session.totalCost")).doubleValue()).isEqualTo(1.0);
	}

	@Test
	void unavailableRepaymentIsNeitherEstimatedNorSelected() throws Exception {
		fakeEstimates(SessionScenarioApiTest::typical);
		String id = createSession("101", "ADAPTIVE", "repayment-unavailable-v1");
		assertThat((List<String>) JsonPath.read(getSession(id), "$.remainingProviders"))
			.containsExactly("BILLING_HISTORY", "PAYMENT_HISTORY");

		String body = advanceBody(id);
		assertThat((String) JsonPath.read(body, "$.step.providerId")).isEqualTo("PAYMENT_HISTORY");
		assertThat((String) JsonPath.read(body, "$.decision.candidates[0].status")).isEqualTo("UNAVAILABLE");
		ArgumentCaptor<List<String>> candidates = ArgumentCaptor.captor();
		verify(predictionClient).estimateValue(any(), candidates.capture(), anyInt());
		assertThat(candidates.getValue()).containsExactly("BILLING_HISTORY", "PAYMENT_HISTORY");
	}

	@Test
	void theStoredSnapshotDecidesEvenIfTheConfigurationChanges() throws Exception {
		String id = createSession("101", "FULL_ACQUISITION");
		assertThat(jdbc.queryForObject("SELECT scenario_snapshot->>'version' FROM acquisition_session "
				+ "WHERE id = CAST(? AS uuid)", String.class, id)).isEqualTo("default-v1");
		// Simulate a later edit of the session's stored scenario: Repayment becomes unavailable for this session only.
		jdbc.update("UPDATE acquisition_session SET scenario_snapshot = jsonb_set(scenario_snapshot, "
				+ "'{providers,REPAYMENT_HISTORY,available}', 'false') WHERE id = CAST(? AS uuid)", id);

		assertThat((String) JsonPath.read(advanceBody(id), "$.step.providerId")).isEqualTo("BILLING_HISTORY");
		assertThat((Boolean) JsonPath.read(getSession(id), "$.providers[0].available")).isFalse();
	}

	@Test
	void unknownScenarioIsABadRequestAndStoresNothing() throws Exception {
		Integer before = jdbc.queryForObject("SELECT count(*) FROM acquisition_session", Integer.class);
		mvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
			.content("{\"recordId\": \"101\", \"policyId\": \"ADAPTIVE\", \"scenarioId\": \"storm-v9\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("UNSUPPORTED_SCENARIO"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM acquisition_session", Integer.class)).isEqualTo(before);
	}

	@Test
	void providersEndpointShowsAnyScenario() throws Exception {
		mvc.perform(get("/api/v1/providers").param("scenarioId", "unreliable-v1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scenarioVersion").value("unreliable-v1"))
			.andExpect(jsonPath("$.providers[2].timeoutMs").value(600));
		mvc.perform(get("/api/v1/providers").param("scenarioId", "storm-v9"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("UNSUPPORTED_SCENARIO"));
	}

	@Test
	void unreliableScenarioKeepsEverySessionConsistent() throws Exception {
		for (String record : List.of("101", "102")) {
			String id = createSession(record, "FULL_ACQUISITION", "unreliable-v1");
			for (int i = 0; i < 4; i++) {
				advanceBody(id);
			}
			String history = getSession(id);
			assertThat((String) JsonPath.read(history, "$.status")).isEqualTo("COMPLETED");
			List<Map<String, Object>> steps = JsonPath.read(history, "$.steps");
			assertThat(steps).hasSize(3);
			long successes = steps.stream().filter(s -> "SUCCESS".equals(s.get("status"))).count();
			long simulated = 0;
			for (Map<String, Object> step : steps) {
				simulated += ((Number) step.get("simulatedLatencyMs")).longValue();
				if ("TIMEOUT".equals(step.get("failureType"))) {
					long timeout = "REPAYMENT_HISTORY".equals(step.get("providerId")) ? 1000 : 600;
					assertThat(((Number) step.get("simulatedLatencyMs")).longValue()).isEqualTo(timeout);
				}
				if (!"SUCCESS".equals(step.get("status"))) {
					assertThat(step.get("failureType")).isIn("FAILURE", "TIMEOUT");
					assertThat(step.get("acquiredFeatures")).isNull();
					assertThat(((Number) step.get("chargedCost")).doubleValue()).isZero();
				}
			}
			assertThat(((Number) JsonPath.read(history, "$.totalCost")).doubleValue()).isEqualTo(successes);
			assertThat(((Number) JsonPath.read(history, "$.accumulatedSimulatedMs")).longValue()).isEqualTo(simulated);
			assertThat((List<Object>) JsonPath.read(history, "$.predictions")).hasSize((int) successes + 1);
			// Repeating the same session gives exactly the same outcome (seeded failures and jitter).
			String again = createSession(record, "FULL_ACQUISITION", "unreliable-v1");
			for (int i = 0; i < 4; i++) {
				advanceBody(again);
			}
			assertThat((List<Object>) JsonPath.read(getSession(again), "$.steps[*].simulatedLatencyMs"))
				.isEqualTo(JsonPath.read(history, "$.steps[*].simulatedLatencyMs"));
		}
	}

}
