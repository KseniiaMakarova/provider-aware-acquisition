package edu.auk.acquisition.policy;

import static edu.auk.acquisition.policy.PolicyContexts.config;
import static edu.auk.acquisition.policy.PolicyContexts.context;
import static edu.auk.acquisition.policy.PolicyContexts.estimate;
import static edu.auk.acquisition.policy.PolicyContexts.initial;
import static edu.auk.acquisition.policy.PolicyContexts.neutralProviders;
import static edu.auk.acquisition.policy.PolicyContexts.provider;
import static edu.auk.acquisition.policy.PolicyContexts.providers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import edu.auk.acquisition.prediction.ValueEstimate;
import edu.auk.acquisition.provider.ProviderMetadata;
import edu.auk.acquisition.scenario.ProviderSettings;

class AdaptivePolicyTest {

	static final AdaptivePolicySettings SETTINGS = new AdaptivePolicySettings("adaptive-test", "test", 100, 0.01, 0.005);

	/** Price of a neutral provider (cost 1, 200 ms, never fails): 0.01 · 1 + 0.005 · 0.2. */
	static final double NEUTRAL_PRICE = 0.011;

	final AdaptivePolicy policy = new AdaptivePolicy(SETTINGS);

	/** Typical initial-state estimates from the validation analysis. */
	static ValueEstimate typicalInitial() {
		return estimate("REPAYMENT_HISTORY", 0.09, "BILLING_HISTORY", 0.007, "PAYMENT_HISTORY", 0.019);
	}

	static String acquired(AcquisitionDecision decision) {
		assertThat(decision).isInstanceOf(AcquisitionDecision.Acquire.class);
		return ((AcquisitionDecision.Acquire) decision).providerId();
	}

	static DecisionDetails.Candidate candidate(AcquisitionDecision decision, String providerId) {
		return decision.details().candidates().stream().filter(c -> c.providerId().equals(providerId)).findFirst()
			.orElseThrow();
	}

	// --- Selection ---

	@Test
	void acquiresTheProviderWithTheHighestPositiveScore() {
		AcquisitionDecision decision = policy.selectNext(initial(neutralProviders(), typicalInitial()));
		assertThat(acquired(decision)).isEqualTo("REPAYMENT_HISTORY");
		DecisionDetails.Candidate repayment = candidate(decision, "REPAYMENT_HISTORY");
		assertThat(repayment.price()).isCloseTo(NEUTRAL_PRICE, within(1e-12));
		assertThat(repayment.score()).isCloseTo(0.09 - NEUTRAL_PRICE, within(1e-12));
		assertThat(repayment.selected()).isTrue();
		assertThat(decision.reason()).contains("REPAYMENT_HISTORY", "0.0790", "0.0900", "0.0110");
	}

	@Test
	void differentObservedInformationLeadsToDifferentProviders() {
		// The estimates come from each record's observed values; here Payment is most informative.
		ValueEstimate paymentFirst = estimate("REPAYMENT_HISTORY", 0.012, "BILLING_HISTORY", 0.004, "PAYMENT_HISTORY",
				0.03);
		assertThat(acquired(policy.selectNext(initial(neutralProviders(), paymentFirst)))).isEqualTo("PAYMENT_HISTORY");
		assertThat(acquired(policy.selectNext(initial(neutralProviders(), typicalInitial()))))
			.isEqualTo("REPAYMENT_HISTORY");
	}

	@Test
	void higherCostCanChangeTheSelection() {
		// Repayment at cost 10: price 0.101 > EIG 0.09, so Payment (score 0.008) is chosen instead.
		AcquisitionDecision decision = policy
			.selectNext(initial(providers(provider("REPAYMENT_HISTORY", 10, 200, true, 0)), typicalInitial()));
		assertThat(acquired(decision)).isEqualTo("PAYMENT_HISTORY");
		assertThat(candidate(decision, "REPAYMENT_HISTORY").score()).isNegative();
	}

	@Test
	void higherLatencyCanChangeTheSelection() {
		// Repayment taking 20 s: latency price 0.1 bits.
		assertThat(acquired(policy
			.selectNext(initial(providers(provider("REPAYMENT_HISTORY", 1, 20_000, true, 0)), typicalInitial()))))
			.isEqualTo("PAYMENT_HISTORY");
	}

	@Test
	void higherFailureRateCanChangeTheSelection() {
		// Repayment failing 90% of the time: 0.1 · 0.09 − 0.01 · 0.1 − 0.001 = 0.007 < Payment's 0.008.
		AcquisitionDecision decision = policy
			.selectNext(initial(providers(provider("REPAYMENT_HISTORY", 1, 200, true, 0.9)), typicalInitial()));
		assertThat(acquired(decision)).isEqualTo("PAYMENT_HISTORY");
		assertThat(candidate(decision, "REPAYMENT_HISTORY").score()).isCloseTo(0.007, within(1e-12));
	}

	@Test
	void timeoutRiskAndSlowCallsLowerTheScore() {
		// Repayment takes 600-1400 ms with a 1000 ms timeout: it succeeds with probability 401/801 and takes
		// about 900 ms on average, so its score drops below Payment's.
		ProviderMetadata slow = provider("REPAYMENT_HISTORY",
				new ProviderSettings(BigDecimal.ONE, 600, true, 0.0, 1000L, 800));
		ValueEstimate estimate = estimate("REPAYMENT_HISTORY", 0.02, "BILLING_HISTORY", 0.007, "PAYMENT_HISTORY", 0.019);
		AcquisitionDecision decision = policy.selectNext(initial(providers(slow), estimate));

		assertThat(acquired(decision)).isEqualTo("PAYMENT_HISTORY");
		DecisionDetails.Candidate repayment = candidate(decision, "REPAYMENT_HISTORY");
		double success = 401.0 / 801;
		assertThat(repayment.timeoutProbability()).isCloseTo(400.0 / 801, within(1e-12));
		assertThat(repayment.successProbability()).isCloseTo(success, within(1e-12));
		assertThat(repayment.timeoutMs()).isEqualTo(1000L);
		assertThat(repayment.score()).isCloseTo(
				success * 0.02 - 0.01 * success - 0.005 * slow.expectedLatencyMs() / 1000, within(1e-12));
	}

	@Test
	void unavailableProvidersAreNeverSelectedEvenWithHighGain() {
		ValueEstimate estimate = estimate("REPAYMENT_HISTORY", 0.5, "BILLING_HISTORY", 0.007, "PAYMENT_HISTORY", 0.019);
		AcquisitionDecision decision = policy
			.selectNext(initial(providers(provider("REPAYMENT_HISTORY", 1, 200, false, 0)), estimate));
		assertThat(acquired(decision)).isEqualTo("PAYMENT_HISTORY");
		DecisionDetails.Candidate repayment = candidate(decision, "REPAYMENT_HISTORY");
		assertThat(repayment.status()).isEqualTo("UNAVAILABLE");
		assertThat(repayment.score()).isNull();
		assertThat(repayment.selected()).isFalse();
	}

	@Test
	void attemptedProvidersAreNotEvaluatedAgain() {
		ValueEstimate estimate = estimate("BILLING_HISTORY", 0.05, "PAYMENT_HISTORY", 0.019);
		AcquisitionDecision decision = policy.selectNext(context(List.of(), List.of("REPAYMENT_HISTORY"),
				neutralProviders(), estimate));
		assertThat(acquired(decision)).isEqualTo("BILLING_HISTORY");
		assertThat(candidate(decision, "REPAYMENT_HISTORY").status()).isEqualTo("FAILED_EARLIER");
	}

	@Test
	void equalScoresAreResolvedInConfigurationOrder() {
		ValueEstimate tie = estimate("REPAYMENT_HISTORY", 0.001, "BILLING_HISTORY", 0.05, "PAYMENT_HISTORY", 0.05);
		assertThat(acquired(policy.selectNext(initial(neutralProviders(), tie)))).isEqualTo("BILLING_HISTORY");
	}

	// --- Stopping ---

	@Test
	void stopsWhenNoProviderIsWorthItsPrice() {
		ValueEstimate afterRepayment = estimate("BILLING_HISTORY", 0.002, "PAYMENT_HISTORY", 0.004);
		AcquisitionDecision decision = policy.selectNext(context(List.of("REPAYMENT_HISTORY"),
				List.of("REPAYMENT_HISTORY"), neutralProviders(), afterRepayment));
		assertThat(decision).isInstanceOfSatisfying(AcquisitionDecision.Stop.class,
				s -> assertThat(s.stopReason()).isEqualTo(StopReason.INSUFFICIENT_EXPECTED_BENEFIT));
		assertThat(decision.reason()).contains("PAYMENT_HISTORY", "-0.0070");
		DecisionDetails details = decision.details();
		assertThat(details.action()).isEqualTo(DecisionDetails.STOP);
		assertThat(details.candidates()).noneMatch(DecisionDetails.Candidate::selected);
		assertThat(candidate(decision, "PAYMENT_HISTORY").score()).isCloseTo(0.004 - NEUTRAL_PRICE, within(1e-12));
		assertThat(candidate(decision, "REPAYMENT_HISTORY").status()).isEqualTo("ACQUIRED");
	}

	@Test
	void canStopWithoutAcquiringAnything() {
		ValueEstimate low = estimate("REPAYMENT_HISTORY", 0.008, "BILLING_HISTORY", 0.001, "PAYMENT_HISTORY", 0.005);
		assertThat(policy.selectNext(initial(neutralProviders(), low)))
			.isInstanceOfSatisfying(AcquisitionDecision.Stop.class,
					s -> assertThat(s.stopReason()).isEqualTo(StopReason.INSUFFICIENT_EXPECTED_BENEFIT));
	}

	@Test
	void aScoreOfExactlyZeroIsNotEnough() {
		AdaptivePolicy noLatencyPrice = new AdaptivePolicy(new AdaptivePolicySettings("v", "", 100, 0.5, 0));
		ValueEstimate exact = estimate("REPAYMENT_HISTORY", 0.5, "BILLING_HISTORY", 0.25, "PAYMENT_HISTORY", 0.5);
		assertThat(noLatencyPrice.selectNext(initial(neutralProviders(), exact)))
			.isInstanceOf(AcquisitionDecision.Stop.class);
	}

	@Test
	void stopsLikeOtherPoliciesWhenNothingIsFeasible() {
		assertThat(policy.selectNext(context(config.providerIds(), config.providerIds(), neutralProviders(), null)))
			.isInstanceOfSatisfying(AcquisitionDecision.Stop.class,
					s -> assertThat(s.stopReason()).isEqualTo(StopReason.ALL_PROVIDERS_ACQUIRED));
		assertThat(policy.selectNext(context(List.of("REPAYMENT_HISTORY"), config.providerIds(), neutralProviders(),
				null)))
			.isInstanceOfSatisfying(AcquisitionDecision.Stop.class,
					s -> assertThat(s.stopReason()).isEqualTo(StopReason.NO_FEASIBLE_PROVIDERS));
	}

	@Test
	void refusesToDecideWithoutEstimates() {
		assertThatThrownBy(() -> policy.selectNext(initial(neutralProviders(), null)))
			.isInstanceOf(IllegalStateException.class);
		ValueEstimate incomplete = estimate("REPAYMENT_HISTORY", 0.09);
		assertThatThrownBy(() -> policy.selectNext(initial(neutralProviders(), incomplete)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("BILLING_HISTORY");
	}

	// --- Explanation and settings ---

	@Test
	void detailsRecordEstimatesParametersAndVersions() {
		DecisionDetails details = policy.selectNext(initial(neutralProviders(), typicalInitial())).details();
		assertThat(details.policyId()).isEqualTo("ADAPTIVE");
		assertThat(details.policyVersion()).isEqualTo("adaptive-test");
		assertThat(details.parameters()).containsEntry("sampleSize", 100)
			.containsEntry("costWeightBitsPerUnit", 0.01)
			.containsEntry("latencyWeightBitsPerSecond", 0.005);
		assertThat(details.estimation())
			.isEqualTo(new DecisionDetails.Estimation("knn-eig-v1", 100, "ref-test", "mask-hgb-test", 0.3));
		assertThat(details.candidates()).extracting(DecisionDetails.Candidate::expectedInformationGainBits)
			.containsExactly(0.09, 0.007, 0.019);
		assertThat(details.candidates()).allSatisfy(c -> {
			assertThat(c.cost()).isEqualByComparingTo("1");
			assertThat(c.latencyMs()).isEqualTo(200);
			assertThat(c.failureRate()).isZero();
		});
	}

	@Test
	void asksForEstimatesWithItsSampleSize() {
		assertThat(policy.valueSampleSize()).hasValue(100);
	}

	@Test
	void settingsAreLoadedFromTheVersionedFile() {
		AdaptivePolicySettings settings = AdaptivePolicySettings
			.load(Path.of("..", "config", "policies", "adaptive-v1.json"));
		assertThat(settings).isEqualTo(new AdaptivePolicySettings("adaptive-v1", settings.description(), 100, 0.01,
				0.005));
	}

	@Test
	void invalidSettingsAreRejected(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("bad.json");
		Files.writeString(file, """
				{"policyVersion": "x", "sampleSize": 100, "costWeightBitsPerUnit": -1, "latencyWeightBitsPerSecond": 0}
				""");
		assertThatThrownBy(() -> AdaptivePolicySettings.load(file)).isInstanceOf(IllegalArgumentException.class);
		Files.writeString(file, """
				{"policyVersion": "x", "costWeightBitsPerUnit": 0.01, "latencyWeightBitsPerSecond": 0}
				""");
		assertThatThrownBy(() -> AdaptivePolicySettings.load(file)).hasMessageContaining("sampleSize");
		assertThatThrownBy(() -> new AdaptivePolicySettings("x", "", 0, 0.01, 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AdaptivePolicySettings(" ", "", 100, 0.01, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
