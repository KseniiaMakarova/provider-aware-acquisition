package edu.auk.acquisition.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/** /advance with the default scenario: all providers available, cost 1, 200 ms, never failing. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = TestDataset.FEATURES_DIR_PROPERTY)
@AutoConfigureMockMvc
class AdvanceApiTest extends AdvanceTestSupport {

	/** Fixture record 101 holds 1000 + the 1-based position of each feature. */
	static double fixtureValue(String feature) {
		return 1001 + config.modelFeatures().indexOf(feature);
	}

	@Test
	void fixedOrderAcquiresEachProviderOnceThenStops() throws Exception {
		String id = createSession("101");
		List<String> acquired = new ArrayList<>();

		for (String provider : config.providerIds()) {
			String body = advanceBody(id);
			acquired.add(provider);
			int n = acquired.size();

			assertThat((String) JsonPath.read(body, "$.outcome")).isEqualTo("ACQUIRED");
			assertThat((String) JsonPath.read(body, "$.step.providerId")).isEqualTo(provider);
			assertThat((int) JsonPath.read(body, "$.step.stepNumber")).isEqualTo(n);
			assertThat((String) JsonPath.read(body, "$.step.status")).isEqualTo("SUCCESS");

			Map<String, Number> stepValues = JsonPath.read(body, "$.step.acquiredFeatures");
			assertThat(stepValues.keySet()).containsExactlyElementsOf(config.provider(provider).orElseThrow().features());
			stepValues.forEach((feature, value) -> assertThat(value.doubleValue()).isEqualTo(fixtureValue(feature)));

			assertThat((List<String>) JsonPath.read(body, "$.session.observedMask"))
				.isEqualTo(config.observedFeatures(acquired));
			assertThat((List<String>) JsonPath.read(body, "$.session.acquiredProviders")).isEqualTo(acquired);
			assertThat((int) JsonPath.read(body, "$.session.latestPrediction.sequenceNumber")).isEqualTo(n);
			assertThat(((Number) JsonPath.read(body, "$.session.latestPrediction.probability")).doubleValue())
				.isCloseTo(0.05 + 0.1 * n, org.assertj.core.data.Offset.offset(1e-9));
			assertThat((int) JsonPath.read(body, "$.step.predictionSequenceNumber")).isEqualTo(n);
			assertThat(((Number) JsonPath.read(body, "$.session.totalCost")).doubleValue()).isEqualTo(n);
			assertThat(((Number) JsonPath.read(body, "$.session.accumulatedSimulatedMs")).longValue()).isEqualTo(200L * n);

			hiddenFeatures(acquired).forEach(name -> assertThat(body).doesNotContain("\"" + name + "\""));
		}

		advance(id).andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("STOPPED"))
			.andExpect(jsonPath("$.step").doesNotExist())
			.andExpect(jsonPath("$.session.status").value("COMPLETED"))
			.andExpect(jsonPath("$.session.stopReason").value("ALL_PROVIDERS_ACQUIRED"))
			.andExpect(jsonPath("$.session.remainingProviders").isEmpty())
			.andExpect(jsonPath("$.session.observedFeatures.length()").value(19));

		advance(id).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("SESSION_NOT_ACTIVE"));

		String history = getSession(id);
		assertThat((List<String>) JsonPath.read(history, "$.steps[*].providerId")).isEqualTo(config.providerIds());
		assertThat((List<Integer>) JsonPath.read(history, "$.predictions[*].sequenceNumber")).containsExactly(0, 1, 2, 3);
		assertThat(stepCount(id)).isEqualTo(3);
	}

	@Test
	void theModelOnlyEverSeesAcquiredGroups() throws Exception {
		String id = createSession("101");
		for (int i = 0; i < config.providers().size(); i++) {
			advanceBody(id);
		}

		ArgumentCaptor<ObservedFeatures> sent = ArgumentCaptor.forClass(ObservedFeatures.class);
		verify(predictionClient, atLeastOnce()).predict(sent.capture());
		List<List<String>> masks = sent.getAllValues().stream().map(ObservedFeatures::mask).toList();
		List<String> acquired = new ArrayList<>();
		List<List<String>> expected = new ArrayList<>();
		expected.add(config.observedFeatures(acquired));
		for (String provider : config.providerIds()) {
			acquired.add(provider);
			expected.add(config.observedFeatures(acquired));
		}
		assertThat(masks).isEqualTo(expected);
	}

	@Test
	void mlOutageDuringAdvanceLeavesTheSessionUnchanged() throws Exception {
		String id = createSession("101");
		doThrow(new PredictionUnavailableException("down")).when(predictionClient).predict(any());

		advance(id).andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error").value("ML_SERVICE_UNAVAILABLE"));

		String unchanged = getSession(id);
		assertThat((List<?>) JsonPath.read(unchanged, "$.steps")).isEmpty();
		assertThat((List<?>) JsonPath.read(unchanged, "$.predictions")).hasSize(1);
		assertThat((List<String>) JsonPath.read(unchanged, "$.observedMask")).isEqualTo(config.initialFeatures());
		assertThat(jdbc.queryForObject("SELECT advance_token IS NULL FROM acquisition_session WHERE id = CAST(? AS uuid)",
				Boolean.class, id)).isTrue();

		fakeModel();
		advance(id).andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("ACQUIRED"))
			.andExpect(jsonPath("$.step.providerId").value("REPAYMENT_HISTORY"));
	}

	@Test
	void concurrentAdvanceOfTheSameSessionGetsAConflict() throws Exception {
		String id = createSession("101");
		CountDownLatch insideFirstAdvance = new CountDownLatch(1);
		CountDownLatch letFirstFinish = new CountDownLatch(1);
		doAnswer(call -> {
			insideFirstAdvance.countDown();
			letFirstFinish.await(10, TimeUnit.SECONDS);
			return prediction(call.getArgument(0));
		}).when(predictionClient).predict(any());

		CompletableFuture<Integer> first = CompletableFuture
			.supplyAsync(() -> unchecked(() -> advance(id).andReturn().getResponse().getStatus()));
		assertThat(insideFirstAdvance.await(10, TimeUnit.SECONDS)).isTrue();

		advance(id).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("ADVANCE_IN_PROGRESS"));

		letFirstFinish.countDown();
		assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
		assertThat(stepCount(id)).isEqualTo(1);
	}

	@Test
	void claimLeftByACrashedRequestExpires() throws Exception {
		String id = createSession("101");
		jdbc.update("UPDATE acquisition_session SET advance_token = gen_random_uuid(), "
				+ "advance_claimed_at = now() - interval '1 hour' WHERE id = CAST(? AS uuid)", id);

		advance(id).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ACQUIRED"));
	}

	@Test
	void freshClaimOfAnotherRequestBlocksTheAdvance() throws Exception {
		String id = createSession("101");
		jdbc.update("UPDATE acquisition_session SET advance_token = gen_random_uuid(), advance_claimed_at = now() "
				+ "WHERE id = CAST(? AS uuid)", id);

		advance(id).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("ADVANCE_IN_PROGRESS"));
		assertThat(stepCount(id)).isZero();
	}

	@Test
	void unknownSessionCannotBeAdvanced() throws Exception {
		advance("00000000-0000-0000-0000-000000000000").andExpect(status().isNotFound());
	}

	interface ThrowingSupplier<T> {

		T get() throws Exception;

	}

	static <T> T unchecked(ThrowingSupplier<T> supplier) {
		try {
			return supplier.get();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
