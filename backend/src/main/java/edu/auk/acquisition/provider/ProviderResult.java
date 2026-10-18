package edu.auk.acquisition.provider;

import java.util.Map;

/** Raw outcome of one provider call, before the executor checks and charges it. */
public record ProviderResult(boolean success, Map<String, Double> values, long simulatedLatencyMs,
		String failureReason) {

	public ProviderResult {
		values = values == null ? Map.of() : Map.copyOf(values);
	}

	public static ProviderResult success(Map<String, Double> values, long simulatedLatencyMs) {
		return new ProviderResult(true, values, simulatedLatencyMs, null);
	}

	public static ProviderResult failure(String reason, long simulatedLatencyMs) {
		return new ProviderResult(false, Map.of(), simulatedLatencyMs, reason);
	}

}
