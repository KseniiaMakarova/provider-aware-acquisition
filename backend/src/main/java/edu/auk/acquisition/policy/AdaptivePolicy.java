package edu.auk.acquisition.policy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

import edu.auk.acquisition.prediction.ValueEstimate;
import edu.auk.acquisition.provider.ProviderMetadata;

/**
 * Initial provider-aware adaptive policy (FR-7, FR-8). For every feasible provider {@code g}
 * (available and not attempted) it combines the expected information gain estimated from the
 * observed state with the provider's operational characteristics:
 *
 * <pre>
 * price_g = λ_cost · (1 − f_g) · cost_g + λ_time · latencySeconds_g
 * score_g = (1 − f_g) · EIG_g − price_g
 * </pre>
 *
 * where {@code f_g} is the failure rate. A failed call reveals nothing and costs nothing, but its
 * latency is still spent, matching the simulation's charging rules. The policy acquires the
 * provider with the highest strictly positive score (ties: configuration order) and stops with
 * {@link StopReason#INSUFFICIENT_EXPECTED_BENEFIT} when no score is positive. It looks one
 * provider ahead.
 */
public class AdaptivePolicy implements AcquisitionPolicy {

	public static final String ID = "ADAPTIVE";

	private final AdaptivePolicySettings settings;

	public AdaptivePolicy(AdaptivePolicySettings settings) {
		this.settings = settings;
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String version() {
		return settings.version();
	}

	@Override
	public String description() {
		return "Acquires the provider with the highest positive net expected value (expected information gain"
				+ " minus the price of its cost and latency); stops when no provider is worth its price";
	}

	@Override
	public Map<String, Object> parameters() {
		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("sampleSize", settings.sampleSize());
		parameters.put("costWeightBitsPerUnit", settings.costWeightBitsPerUnit());
		parameters.put("latencyWeightBitsPerSecond", settings.latencyWeightBitsPerSecond());
		return parameters;
	}

	@Override
	public OptionalInt valueSampleSize() {
		return OptionalInt.of(settings.sampleSize());
	}

	@Override
	public AcquisitionDecision selectNext(AcquisitionContext context) {
		if (context.feasibleProviders().isEmpty()) {
			return FeasibilityStop.of(context, action -> new DecisionDetails(ID, version(), parameters(), action,
					context.estimate().map(DecisionDetails.Estimation::of).orElse(null),
					DecisionDetails.unevaluated(context, null)));
		}
		ValueEstimate estimate = context.estimate()
			.orElseThrow(() -> new IllegalStateException("The adaptive policy needs value estimates"));

		Map<String, Scored> scored = new HashMap<>();
		Scored best = null;
		for (ProviderMetadata provider : context.feasibleProviders()) {
			ValueEstimate.ProviderValue value = estimate.forProvider(provider.id())
				.orElseThrow(() -> new IllegalStateException("No value estimate for feasible provider " + provider.id()));
			Scored s = score(provider, value);
			scored.put(provider.id(), s);
			if (best == null || s.score() > best.score()) {
				best = s;
			}
		}

		boolean acquire = best.score() > 0;
		String selected = acquire ? best.providerId() : null;
		DecisionDetails details = new DecisionDetails(ID, version(), parameters(),
				acquire ? DecisionDetails.ACQUIRE : DecisionDetails.STOP, DecisionDetails.Estimation.of(estimate),
				DecisionDetails.evaluated(context, selected, p -> {
					Scored s = scored.get(p.id());
					return s == null ? null
							: new DecisionDetails.Candidate(p.id(), DecisionDetails.FEASIBLE, p.cost(), p.expectedLatencyMs(),
									p.failureRate(), s.value().expectedInformationGainBits(),
									s.value().expectedProbability(), s.value().expectedAbsoluteChange(), s.price(),
									s.score(), p.id().equals(selected));
				}));
		if (acquire) {
			return new AcquisitionDecision.Acquire(best.providerId(),
					String.format(Locale.ROOT,
							"%s has the highest positive score: %.4f bits (expected information gain %.4f bits,"
									+ " success probability %.2f, price %.4f bits)",
							best.providerId(), best.score(), best.value().expectedInformationGainBits(),
							best.successProbability(), best.price()),
					details);
		}
		return new AcquisitionDecision.Stop(StopReason.INSUFFICIENT_EXPECTED_BENEFIT,
				String.format(Locale.ROOT,
						"No provider is worth its price: the best, %s, has score %.4f bits (expected information gain"
								+ " %.4f bits, price %.4f bits)",
						best.providerId(), best.score(), best.value().expectedInformationGainBits(), best.price()),
				details);
	}

	private Scored score(ProviderMetadata provider, ValueEstimate.ProviderValue value) {
		double success = 1 - provider.failureRate();
		double price = settings.costWeightBitsPerUnit() * success * provider.cost().doubleValue()
				+ settings.latencyWeightBitsPerSecond() * provider.expectedLatencyMs() / 1000.0;
		double score = success * value.expectedInformationGainBits() - price;
		return new Scored(provider.id(), value, success, price, score);
	}

	private record Scored(String providerId, ValueEstimate.ProviderValue value, double successProbability, double price,
			double score) {
	}

}
