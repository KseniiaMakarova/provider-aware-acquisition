package edu.auk.acquisition.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ObservedFeatures;
import edu.auk.acquisition.provider.ProviderMetadata;

class FixedOrderPolicyTest {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	final FixedOrderPolicy policy = new FixedOrderPolicy(config.providerIds());

	static ProviderMetadata metadata(String id) {
		return new ProviderMetadata(id, "", config.provider(id).orElseThrow().features(), BigDecimal.ONE, 200, true);
	}

	static AcquisitionContext context(List<String> acquired, List<String> attempted, List<String> feasible) {
		Map<String, Double> values = new HashMap<>();
		config.observedFeatures(acquired).forEach(f -> values.put(f, 1.0));
		return new AcquisitionContext(ObservedFeatures.of(values, config), 0.3,
				feasible.stream().map(FixedOrderPolicyTest::metadata).toList(), config.providerIds(), attempted,
				BigDecimal.ZERO, 0);
	}

	@Test
	void startsWithTheFirstProviderInOrder() {
		AcquisitionDecision decision = policy.selectNext(context(List.of(), List.of(), config.providerIds()));
		assertThat(decision).isEqualTo(new AcquisitionDecision.Acquire("REPAYMENT_HISTORY", decision.reason()));
	}

	@Test
	void skipsAttemptedAndUnavailableProviders() {
		// Repayment acquired, Billing unavailable (not feasible) -> Payment.
		AcquisitionDecision decision = policy
			.selectNext(context(List.of("REPAYMENT_HISTORY"), List.of("REPAYMENT_HISTORY"), List.of("PAYMENT_HISTORY")));
		assertThat(decision).isInstanceOfSatisfying(AcquisitionDecision.Acquire.class,
				a -> assertThat(a.providerId()).isEqualTo("PAYMENT_HISTORY"));
	}

	@Test
	void decisionDoesNotDependOnThePrediction() {
		AcquisitionContext low = context(List.of(), List.of(), config.providerIds());
		AcquisitionContext high = new AcquisitionContext(low.observed(), 0.99, low.feasibleProviders(),
				low.configuredProviders(), low.attemptedProviders(), low.totalCost(), low.accumulatedSimulatedMs());
		assertThat(policy.selectNext(low)).isEqualTo(policy.selectNext(high));
	}

	@Test
	void stopsWhenEverythingWasAcquired() {
		AcquisitionDecision decision = policy
			.selectNext(context(config.providerIds(), config.providerIds(), List.of()));
		assertThat(decision).isInstanceOfSatisfying(AcquisitionDecision.Stop.class,
				s -> assertThat(s.stopReason()).isEqualTo(StopReason.ALL_PROVIDERS_ACQUIRED));
	}

	@Test
	void stopsWithNoFeasibleProvidersWhenSomeFailedOrAreUnavailable() {
		AcquisitionDecision decision = policy.selectNext(context(List.of("REPAYMENT_HISTORY", "PAYMENT_HISTORY"),
				config.providerIds(), List.of()));
		assertThat(decision).isInstanceOfSatisfying(AcquisitionDecision.Stop.class,
				s -> assertThat(s.stopReason()).isEqualTo(StopReason.NO_FEASIBLE_PROVIDERS));
	}

	@Test
	void customOrderIsRespected() {
		FixedOrderPolicy paymentFirst = new FixedOrderPolicy(
				List.of("PAYMENT_HISTORY", "BILLING_HISTORY", "REPAYMENT_HISTORY"));
		assertThat(paymentFirst.selectNext(context(List.of(), List.of(), config.providerIds())))
			.isInstanceOfSatisfying(AcquisitionDecision.Acquire.class,
					a -> assertThat(a.providerId()).isEqualTo("PAYMENT_HISTORY"));
	}

	@Test
	void registryFindsPoliciesById() {
		PolicyRegistry registry = new PolicyRegistry(List.of(policy));
		assertThat(registry.find("FIXED_ORDER")).containsSame(policy);
		assertThat(registry.find("ORACLE")).isEmpty();
		assertThat(registry.ids()).containsExactly("FIXED_ORDER");
	}

}
