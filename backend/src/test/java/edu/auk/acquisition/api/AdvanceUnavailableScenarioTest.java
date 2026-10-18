package edu.auk.acquisition.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import edu.auk.acquisition.TestDataset;
import edu.auk.acquisition.TestcontainersConfiguration;

/** Billing History is unavailable: it is never selected or called, and no step is recorded (ER-9). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { TestDataset.FEATURES_DIR_PROPERTY,
		"acquisition.scenario-config=src/test/resources/scenarios/billing-unavailable.json" })
@AutoConfigureMockMvc
class AdvanceUnavailableScenarioTest extends AdvanceTestSupport {

	@Test
	void unavailableProviderIsSkippedWithoutAnAttempt() throws Exception {
		String id = createSession("101");
		assertThat((List<String>) JsonPath.read(getSession(id), "$.remainingProviders"))
			.containsExactly("REPAYMENT_HISTORY", "PAYMENT_HISTORY");

		assertThat((String) JsonPath.read(advanceBody(id), "$.step.providerId")).isEqualTo("REPAYMENT_HISTORY");
		assertThat((String) JsonPath.read(advanceBody(id), "$.step.providerId")).isEqualTo("PAYMENT_HISTORY");

		advance(id).andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("STOPPED"))
			.andExpect(jsonPath("$.session.stopReason").value("NO_FEASIBLE_PROVIDERS"));

		String history = getSession(id);
		assertThat((List<String>) JsonPath.read(history, "$.steps[*].providerId"))
			.containsExactly("REPAYMENT_HISTORY", "PAYMENT_HISTORY");
		hiddenFeatures(List.of("REPAYMENT_HISTORY", "PAYMENT_HISTORY"))
			.forEach(n -> assertThat(history).doesNotContain("\"" + n + "\""));
	}

	@Test
	void providersEndpointShowsTheScenario() throws Exception {
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/providers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scenarioVersion").value("test-billing-unavailable"))
			.andExpect(jsonPath("$.providers[1].id").value("BILLING_HISTORY"))
			.andExpect(jsonPath("$.providers[1].available").value(false));
	}

}
