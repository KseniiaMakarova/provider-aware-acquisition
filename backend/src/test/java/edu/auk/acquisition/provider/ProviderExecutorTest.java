package edu.auk.acquisition.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.TestScenarios;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ProviderGroup;
import edu.auk.acquisition.scenario.ProviderScenario;
import edu.auk.acquisition.scenario.ProviderSettings;

class ProviderExecutorTest {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	/** Test double that returns its group with constant values, or fails, or misbehaves. */
	record FakeProvider(ProviderGroup group, boolean fail, List<String> extraFeatures, long latencyMs)
			implements InformationProvider {

		@Override
		public String id() {
			return group.id();
		}

		@Override
		public List<String> suppliedFeatures() {
			return group.features();
		}

		@Override
		public ProviderResult acquire(ProviderRequest request) {
			if (fail) {
				return ProviderResult.failure("down", latencyMs);
			}
			Map<String, Double> values = new HashMap<>();
			group.features().forEach(f -> values.put(f, 7.0));
			extraFeatures.forEach(f -> values.put(f, 1.0));
			return ProviderResult.success(values, latencyMs);
		}

	}

	static ProviderExecutor executor(ProviderScenario scenario, Map<String, FakeProvider> overrides) {
		List<InformationProvider> providers = new ArrayList<>();
		for (ProviderGroup group : config.providers()) {
			providers.add(overrides.getOrDefault(group.id(), new FakeProvider(group, false, List.of(), 200)));
		}
		return new ProviderExecutor(new ProviderRegistry(config, scenario, providers));
	}

	static ProviderGroup group(String id) {
		return config.provider(id).orElseThrow();
	}

	static ProviderScenario costing(String id, String cost, boolean available) {
		return TestScenarios.neutral(Map.of(id, new ProviderSettings(new BigDecimal(cost), 300, available, 0.0)));
	}

	@Test
	void successfulCallIsChargedItsConfiguredCostAndLatency() {
		ProviderAttempt attempt = executor(costing("PAYMENT_HISTORY", "2.5", true), Map.of())
			.execute("PAYMENT_HISTORY", "101", Set.of());

		assertThat(attempt.succeeded()).isTrue();
		assertThat(attempt.values()).containsOnlyKeys(group("PAYMENT_HISTORY").features());
		assertThat(attempt.configuredCost()).isEqualByComparingTo("2.5");
		assertThat(attempt.chargedCost()).isEqualByComparingTo("2.5");
		assertThat(attempt.simulatedLatencyMs()).isEqualTo(200);
		assertThat(attempt.failureReason()).isNull();
	}

	@Test
	void failedCallCostsNothingButKeepsItsLatencyAndReason() {
		ProviderAttempt attempt = executor(costing("PAYMENT_HISTORY", "2.5", true),
				Map.of("PAYMENT_HISTORY", new FakeProvider(group("PAYMENT_HISTORY"), true, List.of(), 450)))
			.execute("PAYMENT_HISTORY", "101", Set.of());

		assertThat(attempt.status()).isEqualTo(ProviderAttempt.Status.FAILED);
		assertThat(attempt.values()).isEmpty();
		assertThat(attempt.configuredCost()).isEqualByComparingTo("2.5");
		assertThat(attempt.chargedCost()).isEqualByComparingTo("0");
		assertThat(attempt.simulatedLatencyMs()).isEqualTo(450);
		assertThat(attempt.failureReason()).isEqualTo("down");
	}

	@Test
	void unavailableProviderIsNeverCalled() {
		ProviderExecutor executor = executor(costing("BILLING_HISTORY", "1", false), Map.of());
		assertThatThrownBy(() -> executor.execute("BILLING_HISTORY", "101", Set.of()))
			.isInstanceOf(ProviderContractException.class)
			.hasMessageContaining("unavailable");
	}

	@Test
	void providerIsNotCalledTwiceInASession() {
		ProviderExecutor executor = executor(TestScenarios.neutral(), Map.of());
		assertThatThrownBy(() -> executor.execute("BILLING_HISTORY", "101", Set.of("BILLING_HISTORY")))
			.isInstanceOf(ProviderContractException.class)
			.hasMessageContaining("already attempted");
	}

	@Test
	void unknownProviderIsRejected() {
		assertThatThrownBy(() -> executor(TestScenarios.neutral(), Map.of()).execute("CREDIT_BUREAU", "101", Set.of()))
			.isInstanceOf(ProviderContractException.class);
	}

	@Test
	void providerReturningAnotherGroupsFeaturesIsStopped() {
		ProviderExecutor executor = executor(TestScenarios.neutral(), Map.of("BILLING_HISTORY",
				new FakeProvider(group("BILLING_HISTORY"), false, List.of("PAY_0"), 200)));
		assertThatThrownBy(() -> executor.execute("BILLING_HISTORY", "101", Set.of()))
			.isInstanceOf(ProviderContractException.class)
			.hasMessageContaining("instead of its declared features");
	}

	@Test
	void attemptValuesCannotBeModified() {
		ProviderAttempt attempt = executor(TestScenarios.neutral(), Map.of()).execute("BILLING_HISTORY", "101", Set.of());
		assertThatThrownBy(() -> attempt.values().put("PAY_0", 1.0)).isInstanceOf(UnsupportedOperationException.class);
	}

}
