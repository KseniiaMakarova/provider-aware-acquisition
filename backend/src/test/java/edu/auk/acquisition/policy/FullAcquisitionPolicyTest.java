package edu.auk.acquisition.policy;

import static edu.auk.acquisition.policy.PolicyContexts.config;
import static edu.auk.acquisition.policy.PolicyContexts.context;
import static edu.auk.acquisition.policy.PolicyContexts.neutralProviders;
import static edu.auk.acquisition.policy.PolicyContexts.provider;
import static edu.auk.acquisition.policy.PolicyContexts.providers;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import edu.auk.acquisition.provider.ProviderMetadata;

class FullAcquisitionPolicyTest {

	final FullAcquisitionPolicy policy = new FullAcquisitionPolicy();

	/** Runs the policy as if every chosen provider succeeded, returning the chosen providers in order. */
	static List<String> run(AcquisitionPolicy policy, List<ProviderMetadata> providers, List<String> failing) {
		List<String> acquired = new ArrayList<>();
		List<String> attempted = new ArrayList<>();
		List<String> chosen = new ArrayList<>();
		while (policy.selectNext(context(acquired, attempted, providers, null)) instanceof AcquisitionDecision.Acquire a) {
			chosen.add(a.providerId());
			attempted.add(a.providerId());
			if (!failing.contains(a.providerId())) {
				acquired.add(a.providerId());
			}
		}
		return chosen;
	}

	@Test
	void acquiresEveryProviderInConfigurationOrderThenStops() {
		assertThat(run(policy, neutralProviders(), List.of())).containsExactlyElementsOf(config.providerIds());
		AcquisitionDecision last = policy.selectNext(context(config.providerIds(), config.providerIds(),
				neutralProviders(), null));
		assertThat(last).isInstanceOfSatisfying(AcquisitionDecision.Stop.class,
				s -> assertThat(s.stopReason()).isEqualTo(StopReason.ALL_PROVIDERS_ACQUIRED));
	}

	@Test
	void skipsUnavailableProvidersAndStopsWithNoFeasibleProviders() {
		List<ProviderMetadata> billingDown = providers(provider("BILLING_HISTORY", 1, 200, false, 0));
		assertThat(run(policy, billingDown, List.of())).containsExactly("REPAYMENT_HISTORY", "PAYMENT_HISTORY");
		AcquisitionDecision last = policy.selectNext(context(List.of("REPAYMENT_HISTORY", "PAYMENT_HISTORY"),
				List.of("REPAYMENT_HISTORY", "PAYMENT_HISTORY"), billingDown, null));
		assertThat(last).isInstanceOfSatisfying(AcquisitionDecision.Stop.class,
				s -> assertThat(s.stopReason()).isEqualTo(StopReason.NO_FEASIBLE_PROVIDERS));
	}

	@Test
	void failedProvidersAreNotRetried() {
		assertThat(run(policy, neutralProviders(), List.of("BILLING_HISTORY")))
			.containsExactlyElementsOf(config.providerIds());
	}

	@Test
	void ignoresCostAndLatencyAndNeedsNoEstimates() {
		List<ProviderMetadata> expensive = providers(provider("REPAYMENT_HISTORY", 1000, 60_000, true, 0.5));
		assertThat(run(policy, expensive, List.of())).containsExactlyElementsOf(config.providerIds());
		assertThat(policy.valueSampleSize()).isEmpty();
	}

	@Test
	void hasItsOwnIdentityInTheDecisionDetails() {
		DecisionDetails details = policy.selectNext(context(List.of(), List.of(), neutralProviders(), null)).details();
		assertThat(details.policyId()).isEqualTo("FULL_ACQUISITION");
		assertThat(details.policyVersion()).isEqualTo("full-acquisition-v1");
		assertThat(details.estimation()).isNull();
	}

	@Test
	void choosesTheSameProvidersAsFixedOrderWithTheDefaultOrder() {
		// Documented limitation: without budgets the two strategies are equivalent in behavior.
		FixedOrderPolicy fixed = new FixedOrderPolicy(config.providerIds());
		List<ProviderMetadata> billingDown = providers(provider("BILLING_HISTORY", 1, 200, false, 0));
		assertThat(run(policy, billingDown, List.of())).isEqualTo(run(fixed, billingDown, List.of()));
	}

}
