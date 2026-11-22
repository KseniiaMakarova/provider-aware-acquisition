package edu.auk.acquisition.scenario;

import java.math.BigDecimal;

/**
 * Operational characteristics of one provider in a scenario, configured independently of the
 * features it supplies (FR-2). All times are simulated: recorded, never waited for.
 *
 * <p>The simulated duration of a call is {@code latencyMs + j}, where {@code j} is drawn
 * reproducibly and uniformly from {@code 0..latencyJitterMs}. A call that takes longer than
 * {@code timeoutMs} times out.
 *
 * @param cost            charged for a successful call, in experimental units
 * @param latencyMs       simulated duration of a call without jitter
 * @param available       unavailable providers are not feasible and are never called (ER-9)
 * @param failureRate     probability that a call fails, decided deterministically from the scenario seed
 * @param timeoutMs       simulated timeout of a call, or {@code null} for none
 * @param latencyJitterMs largest extra simulated duration added to {@code latencyMs}; 0 for none
 */
public record ProviderSettings(BigDecimal cost, long latencyMs, boolean available, double failureRate, Long timeoutMs,
		long latencyJitterMs) {

	/** Settings without timeout and jitter. */
	public ProviderSettings(BigDecimal cost, long latencyMs, boolean available, double failureRate) {
		this(cost, latencyMs, available, failureRate, null, 0);
	}

	/** Probability that a call exceeds the timeout. */
	public double timeoutProbability() {
		if (timeoutMs == null) {
			return 0;
		}
		long withinTimeout = timeoutMs - latencyMs; // jitter values j <= withinTimeout do not time out
		long outcomes = latencyJitterMs + 1;
		if (withinTimeout < 0) {
			return 1;
		}
		if (withinTimeout >= latencyJitterMs) {
			return 0;
		}
		return (double) (latencyJitterMs - withinTimeout) / outcomes;
	}

	/** Mean simulated time one call adds to the session: its duration, or the timeout if it is reached. */
	public double expectedChargedLatencyMs() {
		double sum = 0;
		for (long j = 0; j <= latencyJitterMs; j++) {
			long duration = latencyMs + j;
			sum += timeoutMs == null ? duration : Math.min(duration, timeoutMs);
		}
		return sum / (latencyJitterMs + 1);
	}

	/** Probability that a call returns the provider's features: it neither times out nor fails. */
	public double successProbability() {
		return (1 - failureRate) * (1 - timeoutProbability());
	}

}
