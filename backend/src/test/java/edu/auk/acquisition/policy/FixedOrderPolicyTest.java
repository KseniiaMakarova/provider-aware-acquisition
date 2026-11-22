package edu.auk.acquisition.policy;

import static edu.auk.acquisition.policy.PolicyContexts.config;
import static edu.auk.acquisition.policy.PolicyContexts.neutralProviders;
import static edu.auk.acquisition.policy.PolicyContexts.provider;
import static edu.auk.acquisition.policy.PolicyContexts.providers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class FixedOrderPolicyTest {

	final FixedOrderPolicy policy = new FixedOrderPolicy(config.providerIds());

	static AcquisitionContext context(List<String> acquired, List<String> attempted) {
		return PolicyContexts.context(acquired, attempted, neutralProviders(), null);
	}

	@Test
	void startsWithTheFirstProviderInOrder() {
		AcquisitionDecision decision = policy.selectNext(context(List.of(), List.of()));
		assertThat(decision).isInstanceOfSatisfying(AcquisitionDecision.Acquire.class,
				a -> assertThat(a.providerId()).isEqualTo("REPAYMENT_HISTORY"));
	}

	@Test
	void detailsListEveryProviderWithoutEstimates() {
		DecisionDetails details = policy.selectNext(context(List.of(), List.of())).details();
		assertThat(details.policyId()).isEqualTo("FIXED_ORDER");
		assertThat(details.policyVersion()).isEqualTo("fixed-order-v1");
		assertThat(details.action()).isEqualTo(DecisionDetails.ACQUIRE);
		assertThat(details.parameters()).containsEntry("order", config.providerIds());
		assertThat(details.estimation()).isNull();
		assertThat(details.candidates()).extracting(DecisionDetails.Candidate::providerId)
			.containsExactlyElementsOf(config.providerIds());
		assertThat(details.candidates()).extracting(DecisionDetails.Candidate::selected).containsExactly(true, false, false);
		assertThat(details.candidates()).allSatisfy(c -> {
			assertThat(c.status()).isEqualTo("FEASIBLE");
			assertThat(c.expectedInformationGainBits()).isNull();
			assertThat(c.score()).isNull();
		});
	}

	@Test
	void skipsAttemptedAndUnavailableProviders() {
		// Repayment acquired, Billing unavailable -> Payment.
		AcquisitionDecision decision = policy.selectNext(PolicyContexts.context(List.of("REPAYMENT_HISTORY"),
				List.of("REPAYMENT_HISTORY"), providers(provider("BILLING_HISTORY", 1, 200, false, 0)), null));
		assertThat(decision).isInstanceOfSatisfying(AcquisitionDecision.Acquire.class,
				a -> assertThat(a.providerId()).isEqualTo("PAYMENT_HISTORY"));
		assertThat(decision.details().candidates()).extracting(DecisionDetails.Candidate::status)
			.containsExactly("ACQUIRED", "UNAVAILABLE", "FEASIBLE");
	}

	@Test
	void decisionDoesNotDependOnThePrediction() {
		AcquisitionContext low = context(List.of(), List.of());
		AcquisitionContext high = new AcquisitionContext(low.observed(), 0.99, low.providers(), low.feasibleProviders(),
				low.attemptedProviders(), low.totalCost(), low.accumulatedSimulatedMs(), null);
		assertThat(policy.selectNext(low)).isEqualTo(policy.selectNext(high));
	}

	@Test
	void stopsWhenEverythingWasAcquired() {
		AcquisitionDecision decision = policy.selectNext(context(config.providerIds(), config.providerIds()));
		assertThat(decision).isInstanceOfSatisfying(AcquisitionDecision.Stop.class,
				s -> assertThat(s.stopReason()).isEqualTo(StopReason.ALL_PROVIDERS_ACQUIRED));
		assertThat(decision.details().action()).isEqualTo(DecisionDetails.STOP);
		assertThat(decision.details().candidates()).noneMatch(DecisionDetails.Candidate::selected);
	}

	@Test
	void stopsWithNoFeasibleProvidersWhenSomeFailedOrAreUnavailable() {
		AcquisitionDecision decision = policy
			.selectNext(context(List.of("REPAYMENT_HISTORY", "PAYMENT_HISTORY"), config.providerIds()));
		assertThat(decision).isInstanceOfSatisfying(AcquisitionDecision.Stop.class,
				s -> assertThat(s.stopReason()).isEqualTo(StopReason.NO_FEASIBLE_PROVIDERS));
		assertThat(decision.details().candidates()).extracting(DecisionDetails.Candidate::status)
			.containsExactly("ACQUIRED", "FAILED_EARLIER", "ACQUIRED");
	}

	@Test
	void customOrderIsRespected() {
		FixedOrderPolicy paymentFirst = new FixedOrderPolicy(
				List.of("PAYMENT_HISTORY", "BILLING_HISTORY", "REPAYMENT_HISTORY"));
		assertThat(paymentFirst.selectNext(context(List.of(), List.of())))
			.isInstanceOfSatisfying(AcquisitionDecision.Acquire.class,
					a -> assertThat(a.providerId()).isEqualTo("PAYMENT_HISTORY"));
	}

	@Test
	void needsNoValueEstimates() {
		assertThat(policy.valueSampleSize()).isEmpty();
	}

	@Test
	void registryFindsPoliciesByIdInRegistrationOrder() {
		FullAcquisitionPolicy full = new FullAcquisitionPolicy();
		PolicyRegistry registry = new PolicyRegistry(List.of(policy, full));
		assertThat(registry.find("FIXED_ORDER")).containsSame(policy);
		assertThat(registry.find("ORACLE")).isEmpty();
		assertThat(registry.ids()).containsExactly("FIXED_ORDER", "FULL_ACQUISITION");
		assertThat(registry.all()).containsExactly(policy, full);
	}

	@Test
	void registryRejectsDuplicateIds() {
		assertThatThrownBy(() -> new PolicyRegistry(List.of(policy, new FixedOrderPolicy(List.of()))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("FIXED_ORDER");
	}

}
