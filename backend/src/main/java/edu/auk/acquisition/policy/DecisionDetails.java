package edu.auk.acquisition.policy;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import edu.auk.acquisition.prediction.ValueEstimate;
import edu.auk.acquisition.provider.ProviderMetadata;

/**
 * Everything needed to reconstruct and explain one decision: the policy and its parameters, the
 * value estimate it used (if any) and, for every configured provider, its characteristics,
 * whether it could be chosen, its estimate and score, and whether it was selected.
 *
 * @param action     {@code ACQUIRE} or {@code STOP}
 * @param estimation the value estimate's method and versions, or {@code null} for policies without estimates
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DecisionDetails(String policyId, String policyVersion, Map<String, Object> parameters, String action,
		Estimation estimation, List<Candidate> candidates) {

	public static final String ACQUIRE = "ACQUIRE";

	public static final String STOP = "STOP";

	public DecisionDetails {
		parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
		candidates = List.copyOf(candidates);
	}

	/**
	 * @param currentProbability the model's prediction for the observed state, as returned with the estimate
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Estimation(String method, int sampleSize, String referenceVersion, String modelVersion,
			double currentProbability) {

		static Estimation of(ValueEstimate estimate) {
			return new Estimation(estimate.method(), estimate.sampleSize(), estimate.referenceVersion(),
					estimate.modelVersion(), estimate.currentProbability());
		}

	}

	/**
	 * One provider as the policy saw it: its characteristics in the session's scenario and, if the
	 * policy evaluated it, its estimate, price and score (otherwise {@code null}).
	 *
	 * @param status             FEASIBLE, ACQUIRED, FAILED_EARLIER or UNAVAILABLE
	 * @param successProbability {@code s = (1 − failureRate)·(1 − timeoutProbability)}
	 * @param expectedLatencyMs  mean simulated time of a call, capped at the timeout
	 * @param price              operational price in bits: {@code λ_cost·s·cost + λ_time·expectedLatency}
	 * @param score              net expected value in bits: {@code s·EIG − price}
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Candidate(String providerId, String status, BigDecimal cost, long latencyMs, long latencyJitterMs,
			Long timeoutMs, double failureRate, double timeoutProbability, double successProbability,
			double expectedLatencyMs, Double expectedInformationGainBits, Double expectedProbability,
			Double expectedAbsoluteChange, Double price, Double score, boolean selected) {

		static Candidate of(ProviderMetadata p, String status, ValueEstimate.ProviderValue value, Double price,
				Double score, boolean selected) {
			return new Candidate(p.id(), status, p.cost(), p.latencyMs(), p.latencyJitterMs(), p.timeoutMs(),
					p.failureRate(), p.timeoutProbability(), p.successProbability(), p.expectedLatencyMs(),
					value == null ? null : value.expectedInformationGainBits(),
					value == null ? null : value.expectedProbability(),
					value == null ? null : value.expectedAbsoluteChange(), price, score, selected);
		}

	}

	public static final String FEASIBLE = "FEASIBLE";

	/** Status of each configured provider in the context. */
	static String status(ProviderMetadata provider, AcquisitionContext context) {
		if (context.acquiredProviders().contains(provider.id())) {
			return "ACQUIRED";
		}
		if (context.attemptedProviders().contains(provider.id())) {
			return "FAILED_EARLIER";
		}
		return provider.available() ? FEASIBLE : "UNAVAILABLE";
	}

	/** Candidates without estimates, for policies that do not evaluate providers. */
	static List<Candidate> unevaluated(AcquisitionContext context, String selected) {
		return evaluated(context, selected, p -> null);
	}

	static List<Candidate> evaluated(AcquisitionContext context, String selected,
			Function<ProviderMetadata, Candidate> evaluation) {
		return context.providers().stream().map(p -> {
			Candidate evaluated = evaluation.apply(p);
			if (evaluated != null) {
				return evaluated;
			}
			return Candidate.of(p, status(p, context), null, null, null, p.id().equals(selected));
		}).toList();
	}

}
