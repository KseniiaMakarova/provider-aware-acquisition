package edu.auk.acquisition.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import edu.auk.acquisition.TestDataset;
import edu.auk.acquisition.TestcontainersConfiguration;
import edu.auk.acquisition.feature.ObservedFeatures;
import edu.auk.acquisition.prediction.PredictionUnavailableException;
import edu.auk.acquisition.prediction.ValueEstimate;
import edu.auk.acquisition.prediction.ValueEstimate.ProviderValue;

/**
 * ADAPTIVE and FULL_ACQUISITION sessions through the API with the default scenario (cost 1,
 * 200 ms, never failing). Value estimates are faked; the adaptive settings are the real
 * versioned file (λ_cost 0.01, λ_time 0.005, K 100), so a neutral provider costs 0.011 bits.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = TestDataset.FEATURES_DIR_PROPERTY)
@AutoConfigureMockMvc
class AdaptiveAdvanceApiTest extends AdvanceTestSupport {

	/** Typical validation estimates: Repayment is worth it at first, nothing afterwards. */
	static Map<String, Double> typical(Set<String> acquired) {
		return acquired.isEmpty()
				? Map.of("REPAYMENT_HISTORY", 0.09, "BILLING_HISTORY", 0.007, "PAYMENT_HISTORY", 0.019)
				: Map.of("BILLING_HISTORY", 0.002, "PAYMENT_HISTORY", 0.004);
	}

	@Test
	void adaptiveSessionAcquiresUsefulInformationThenStopsEarly() throws Exception {
		fakeEstimates(AdaptiveAdvanceApiTest::typical);
		String id = createSession("101", "ADAPTIVE");

		String first = advanceBody(id);
		assertThat((String) JsonPath.read(first, "$.outcome")).isEqualTo("ACQUIRED");
		assertThat((String) JsonPath.read(first, "$.step.providerId")).isEqualTo("REPAYMENT_HISTORY");
		assertThat((String) JsonPath.read(first, "$.decision.policyId")).isEqualTo("ADAPTIVE");
		assertThat((String) JsonPath.read(first, "$.decision.policyVersion")).isEqualTo("adaptive-v1");
		assertThat(((Number) JsonPath.read(first, "$.decision.candidates[0].score")).doubleValue())
			.isCloseTo(0.079, within(1e-9));
		assertThat((String) JsonPath.read(first, "$.decisionReason")).contains("REPAYMENT_HISTORY");

		String second = advanceBody(id);
		assertThat((String) JsonPath.read(second, "$.outcome")).isEqualTo("STOPPED");
		assertThat((String) JsonPath.read(second, "$.session.stopReason")).isEqualTo("INSUFFICIENT_EXPECTED_BENEFIT");
		assertThat((String) JsonPath.read(second, "$.session.stopExplanation")).startsWith("No provider is worth its price");
		assertThat((String) JsonPath.read(second, "$.decision.action")).isEqualTo("STOP");

		// Everything needed to explain both decisions is stored and comes back with the session.
		String history = getSession(id);
		assertThat((List<Object>) JsonPath.read(history, "$.steps")).hasSize(1);
		assertThat((List<Double>) JsonPath.read(history, "$.steps[0].decisionDetails.candidates[*].expectedInformationGainBits"))
			.containsExactly(0.09, 0.007, 0.019);
		assertThat((List<Boolean>) JsonPath.read(history, "$.steps[0].decisionDetails.candidates[*].selected"))
			.containsExactly(true, false, false);
		assertThat((Number) JsonPath.read(history, "$.steps[0].decisionDetails.parameters.costWeightBitsPerUnit"))
			.isEqualTo(0.01);
		assertThat((String) JsonPath.read(history, "$.steps[0].decisionDetails.estimation.referenceVersion"))
			.isEqualTo("ref-test");
		assertThat((List<String>) JsonPath.read(history, "$.stopDecision.candidates[*].status"))
			.containsExactly("ACQUIRED", "FEASIBLE", "FEASIBLE");
		assertThat(((Number) JsonPath.read(history, "$.stopDecision.candidates[2].score")).doubleValue())
			.isCloseTo(0.004 - 0.011, within(1e-9));
		assertThat((String) JsonPath.read(history, "$.stopExplanation")).contains("PAYMENT_HISTORY");
		assertThat(((Number) JsonPath.read(history, "$.totalCost")).doubleValue()).isEqualTo(1.0);
		assertThat(jdbc.queryForObject("SELECT stop_decision_details->>'action' FROM acquisition_session "
				+ "WHERE id = CAST(? AS uuid)", String.class, id)).isEqualTo("STOP");
		assertThat(jdbc.queryForObject("SELECT decision_details->>'policyId' FROM acquisition_step "
				+ "WHERE session_id = CAST(? AS uuid)", String.class, id)).isEqualTo("ADAPTIVE");
	}

	@Test
	void estimatesAreRequestedForFeasibleProvidersFromTheObservedStateOnly() throws Exception {
		fakeEstimates(AdaptiveAdvanceApiTest::typical);
		String id = createSession("101", "ADAPTIVE");
		advanceBody(id);
		advanceBody(id);

		ArgumentCaptor<ObservedFeatures> observed = ArgumentCaptor.forClass(ObservedFeatures.class);
		ArgumentCaptor<List<String>> candidates = ArgumentCaptor.captor();
		ArgumentCaptor<Integer> sampleSize = ArgumentCaptor.forClass(Integer.class);
		verify(predictionClient, times(2)).estimateValue(observed.capture(), candidates.capture(), sampleSize.capture());
		assertThat(candidates.getAllValues()).containsExactly(
				List.of("REPAYMENT_HISTORY", "BILLING_HISTORY", "PAYMENT_HISTORY"),
				List.of("BILLING_HISTORY", "PAYMENT_HISTORY"));
		assertThat(observed.getAllValues().get(0).mask()).containsExactlyElementsOf(config.initialFeatures());
		assertThat(observed.getAllValues().get(1).acquiredProviders()).containsExactly("REPAYMENT_HISTORY");
		assertThat(sampleSize.getAllValues()).containsOnly(100);
	}

	@Test
	void sessionCanStopWithoutAcquiringAnyProvider() throws Exception {
		fakeEstimates(acquired -> Map.of("REPAYMENT_HISTORY", 0.008, "BILLING_HISTORY", 0.001, "PAYMENT_HISTORY", 0.005));
		String id = createSession("101", "ADAPTIVE");

		advance(id).andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("STOPPED"))
			.andExpect(jsonPath("$.step").doesNotExist())
			.andExpect(jsonPath("$.session.stopReason").value("INSUFFICIENT_EXPECTED_BENEFIT"))
			.andExpect(jsonPath("$.session.totalCost").value(0))
			.andExpect(jsonPath("$.session.predictions.length()").value(1));
		assertThat(stepCount(id)).isZero();

		String history = getSession(id);
		hiddenFeatures(List.of()).forEach(n -> assertThat(history).doesNotContain("\"" + n + "\""));
	}

	@Test
	void valueEstimationOutageLeavesTheSessionUnchanged() throws Exception {
		String id = createSession("101", "ADAPTIVE");
		doThrow(new PredictionUnavailableException("down")).when(predictionClient)
			.estimateValue(any(), any(), anyInt());

		advance(id).andExpect(status().isServiceUnavailable());
		assertThat(stepCount(id)).isZero();
		assertThat((String) JsonPath.read(getSession(id), "$.status")).isEqualTo("ACTIVE");

		fakeEstimates(AdaptiveAdvanceApiTest::typical); // the claim was released, so the session can continue
		advance(id).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ACQUIRED"));
	}

	@Test
	void concurrentAdaptiveAdvanceGetsAConflict() throws Exception {
		String id = createSession("101", "ADAPTIVE");
		CountDownLatch insideFirstAdvance = new CountDownLatch(1);
		CountDownLatch letFirstFinish = new CountDownLatch(1);
		doAnswer(call -> {
			insideFirstAdvance.countDown();
			letFirstFinish.await(10, TimeUnit.SECONDS);
			return new ValueEstimate("knn-eig-v1", 100, "ref-test", "mask-hgb-test", 0.05,
					List.of(new ProviderValue("REPAYMENT_HISTORY", 0.09, 0.3, 0.02),
							new ProviderValue("BILLING_HISTORY", 0.0, 0.3, 0.0),
							new ProviderValue("PAYMENT_HISTORY", 0.0, 0.3, 0.0)));
		}).when(predictionClient).estimateValue(any(), any(), anyInt());

		CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> {
			try {
				return advance(id).andReturn().getResponse().getStatus();
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		assertThat(insideFirstAdvance.await(10, TimeUnit.SECONDS)).isTrue();
		advance(id).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("ADVANCE_IN_PROGRESS"));

		letFirstFinish.countDown();
		assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
		assertThat(stepCount(id)).isEqualTo(1);
	}

	@Test
	void fullAcquisitionAcquiresEveryProviderWithoutEstimates() throws Exception {
		String id = createSession("101", "FULL_ACQUISITION");
		for (String provider : config.providerIds()) {
			String body = advanceBody(id);
			assertThat((String) JsonPath.read(body, "$.step.providerId")).isEqualTo(provider);
			assertThat((String) JsonPath.read(body, "$.decision.policyId")).isEqualTo("FULL_ACQUISITION");
		}
		advance(id).andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("STOPPED"))
			.andExpect(jsonPath("$.session.stopReason").value("ALL_PROVIDERS_ACQUIRED"))
			.andExpect(jsonPath("$.session.stopExplanation").value("All configured providers acquired"))
			.andExpect(jsonPath("$.session.totalCost").value(3))
			.andExpect(jsonPath("$.session.predictions.length()").value(4));
		verify(predictionClient, never()).estimateValue(any(), any(), anyInt());
	}

	@Test
	void fixedOrderStopIsExplainedToo() throws Exception {
		String id = createSession("101");
		for (int i = 0; i <= config.providerIds().size(); i++) {
			advanceBody(id);
		}
		String history = getSession(id);
		assertThat((String) JsonPath.read(history, "$.stopExplanation")).isEqualTo("All configured providers acquired");
		assertThat((String) JsonPath.read(history, "$.stopDecision.policyId")).isEqualTo("FIXED_ORDER");
		assertThat((List<Object>) JsonPath.read(history, "$.steps[*].decisionDetails")).hasSize(3).doesNotContainNull();
	}

	@Test
	void policiesEndpointListsTheStrategiesWithTheirParameters() throws Exception {
		mvc.perform(get("/api/v1/policies"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.policies[*].id").value(contains("ADAPTIVE", "FIXED_ORDER",
					"FULL_ACQUISITION")))
			.andExpect(jsonPath("$.policies[0].version").value("adaptive-v1"))
			.andExpect(jsonPath("$.policies[0].usesValueEstimates").value(true))
			.andExpect(jsonPath("$.policies[0].parameters.sampleSize").value(100))
			.andExpect(jsonPath("$.policies[0].parameters.costWeightBitsPerUnit").value(0.01))
			.andExpect(jsonPath("$.policies[0].parameters.latencyWeightBitsPerSecond").value(0.005))
			.andExpect(jsonPath("$.policies[1].usesValueEstimates").value(false))
			.andExpect(jsonPath("$.policies[2].version").value("full-acquisition-v1"));
	}

	@Test
	void providersEndpointShowsTheFailureRate() throws Exception {
		mvc.perform(get("/api/v1/providers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.providers[0].failureRate").value(0.0));
	}

}
