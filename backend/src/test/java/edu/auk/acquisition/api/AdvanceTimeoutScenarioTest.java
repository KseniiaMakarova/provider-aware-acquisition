package edu.auk.acquisition.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import edu.auk.acquisition.TestDataset;
import edu.auk.acquisition.TestcontainersConfiguration;

/**
 * Payment History always times out (800 ms against a 500 ms timeout); Billing History always
 * fails. A timeout is a failed step of type TIMEOUT: charged the timeout, no cost, no features,
 * no new prediction, not retried.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { TestDataset.FEATURES_DIR_PROPERTY,
		"acquisition.scenarios-dir=src/test/resources/scenarios", "acquisition.default-scenario=test-timeouts" })
@AutoConfigureMockMvc
class AdvanceTimeoutScenarioTest extends AdvanceTestSupport {

	@Test
	void timedOutCallRevealsNothingCostsNothingAndIsChargedTheTimeout() throws Exception {
		String id = createSession("101", "FULL_ACQUISITION");
		advanceBody(id); // Repayment History succeeds
		advanceBody(id); // Billing History fails

		String timedOut = advanceBody(id);
		assertThat((String) JsonPath.read(timedOut, "$.outcome")).isEqualTo("PROVIDER_FAILED");
		assertThat((String) JsonPath.read(timedOut, "$.step.providerId")).isEqualTo("PAYMENT_HISTORY");
		assertThat((String) JsonPath.read(timedOut, "$.step.failureType")).isEqualTo("TIMEOUT");
		assertThat((String) JsonPath.read(timedOut, "$.step.failureReason")).contains("500 ms");
		assertThat(((Number) JsonPath.read(timedOut, "$.step.simulatedLatencyMs")).longValue()).isEqualTo(500);
		assertThat(((Number) JsonPath.read(timedOut, "$.step.chargedCost")).doubleValue()).isZero();
		assertThat((Object) JsonPath.read(timedOut, "$.step.acquiredFeatures")).isNull();
		assertThat((Object) JsonPath.read(timedOut, "$.step.predictionSequenceNumber")).isNull();

		advance(id).andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("STOPPED"))
			.andExpect(jsonPath("$.session.stopReason").value("NO_FEASIBLE_PROVIDERS"));
		String history = getSession(id);
		assertThat((List<String>) JsonPath.read(history, "$.steps[*].failureType"))
			.containsExactly(null, "FAILURE", "TIMEOUT");
		assertThat(((Number) JsonPath.read(history, "$.accumulatedSimulatedMs")).longValue()).isEqualTo(200 + 450 + 500);
		assertThat(((Number) JsonPath.read(history, "$.totalCost")).doubleValue()).isEqualTo(1.0);
		assertThat((List<Object>) JsonPath.read(history, "$.predictions")).hasSize(2);
		assertThat(jdbc.queryForObject("SELECT failure_type FROM acquisition_step WHERE session_id = CAST(? AS uuid) "
				+ "AND provider_id = 'PAYMENT_HISTORY'", String.class, id)).isEqualTo("TIMEOUT");
		hiddenFeatures(List.of("REPAYMENT_HISTORY")).forEach(n -> assertThat(history).doesNotContain("\"" + n + "\""));
	}

	@Test
	void adaptivePolicyNeverChoosesAProviderThatAlwaysTimesOut() throws Exception {
		fakeEstimates(acquired -> Map.of("REPAYMENT_HISTORY", 0.01, "BILLING_HISTORY", 0.5, "PAYMENT_HISTORY", 0.5));
		String id = createSession("101", "ADAPTIVE");

		advance(id).andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("STOPPED"))
			.andExpect(jsonPath("$.session.stopReason").value("INSUFFICIENT_EXPECTED_BENEFIT"))
			.andExpect(jsonPath("$.decision.candidates[2].timeoutProbability").value(1.0))
			.andExpect(jsonPath("$.decision.candidates[2].successProbability").value(0.0))
			.andExpect(jsonPath("$.decision.candidates[2].expectedLatencyMs").value(500.0));
	}

}
