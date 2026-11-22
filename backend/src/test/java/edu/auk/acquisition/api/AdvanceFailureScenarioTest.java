package edu.auk.acquisition.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
 * Billing History always fails (latency 450 ms); Payment History costs 2.5. A failed call is
 * recorded, charges no cost, adds its latency, reveals nothing and makes no new prediction;
 * the failed provider is not tried again.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { TestDataset.FEATURES_DIR_PROPERTY,
		"acquisition.scenario-config=src/test/resources/scenarios/billing-fails.json" })
@AutoConfigureMockMvc
class AdvanceFailureScenarioTest extends AdvanceTestSupport {

	@Test
	void failedProviderIsRecordedButChargesNothingAndRevealsNothing() throws Exception {
		String id = createSession("101");
		advanceBody(id); // Repayment History

		String failed = advanceBody(id);
		assertThat((String) JsonPath.read(failed, "$.outcome")).isEqualTo("PROVIDER_FAILED");
		assertThat((String) JsonPath.read(failed, "$.step.providerId")).isEqualTo("BILLING_HISTORY");
		assertThat((String) JsonPath.read(failed, "$.step.status")).isEqualTo("FAILED");
		assertThat((String) JsonPath.read(failed, "$.step.failureReason")).isEqualTo("Simulated provider failure");
		assertThat((Object) JsonPath.read(failed, "$.step.acquiredFeatures")).isNull();
		assertThat((Object) JsonPath.read(failed, "$.step.predictionSequenceNumber")).isNull();
		assertThat(((Number) JsonPath.read(failed, "$.step.chargedCost")).doubleValue()).isZero();
		assertThat(((Number) JsonPath.read(failed, "$.step.simulatedLatencyMs")).longValue()).isEqualTo(450);
		assertThat((List<?>) JsonPath.read(failed, "$.session.predictions")).hasSize(2);
		assertThat((List<String>) JsonPath.read(failed, "$.session.acquiredProviders"))
			.containsExactly("REPAYMENT_HISTORY");
		assertThat(((Number) JsonPath.read(failed, "$.session.totalCost")).doubleValue()).isEqualTo(1.0);
		assertThat(((Number) JsonPath.read(failed, "$.session.accumulatedSimulatedMs")).longValue()).isEqualTo(650);
		assertThat((List<String>) JsonPath.read(failed, "$.session.remainingProviders"))
			.containsExactly("PAYMENT_HISTORY");
		hiddenFeatures(List.of("REPAYMENT_HISTORY")).forEach(n -> assertThat(failed).doesNotContain("\"" + n + "\""));

		String payment = advanceBody(id);
		assertThat((String) JsonPath.read(payment, "$.step.providerId")).isEqualTo("PAYMENT_HISTORY");
		assertThat(((Number) JsonPath.read(payment, "$.session.totalCost")).doubleValue()).isEqualTo(3.5);
		assertThat(((Number) JsonPath.read(payment, "$.session.accumulatedSimulatedMs")).longValue()).isEqualTo(850);

		advance(id).andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("STOPPED"))
			.andExpect(jsonPath("$.session.stopReason").value("NO_FEASIBLE_PROVIDERS"));
		assertThat(stepCount(id)).isEqualTo(3);
	}

	@Test
	void adaptivePolicyNeverChoosesAProviderThatAlwaysFails() throws Exception {
		// Billing fails with rate 1: its expected gain counts for nothing, but its 450 ms latency is still a price.
		fakeEstimates(acquired -> acquired.isEmpty()
				? Map.of("REPAYMENT_HISTORY", 0.05, "BILLING_HISTORY", 0.9, "PAYMENT_HISTORY", 0.02)
				: Map.of("BILLING_HISTORY", 0.9, "PAYMENT_HISTORY", 0.03));
		String id = createSession("101", "ADAPTIVE");

		String first = advanceBody(id);
		assertThat((String) JsonPath.read(first, "$.step.providerId")).isEqualTo("REPAYMENT_HISTORY");
		assertThat(((Number) JsonPath.read(first, "$.decision.candidates[1].score")).doubleValue())
			.isCloseTo(-0.005 * 0.45, within(1e-12));
		// Payment costs 2.5: price 0.01 * 2.5 + 0.005 * 0.2 = 0.026 < 0.03.
		assertThat((String) JsonPath.read(advanceBody(id), "$.step.providerId")).isEqualTo("PAYMENT_HISTORY");

		advance(id).andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("STOPPED"))
			.andExpect(jsonPath("$.session.stopReason").value("INSUFFICIENT_EXPECTED_BENEFIT"));
		assertThat((List<String>) JsonPath.read(getSession(id), "$.steps[*].providerId"))
			.containsExactly("REPAYMENT_HISTORY", "PAYMENT_HISTORY");
	}

}
