package edu.auk.acquisition.provider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Raw outcome of one provider call, before the executor checks and charges it. */
public record ProviderResult(boolean success, Map<String, Double> values, long simulatedLatencyMs,
		String failureReason) {

	public ProviderResult {
		if (!success && (failureReason == null || failureReason.isBlank())) {
			// A failed step is stored with its reason; without one it could not be saved.
			throw new IllegalArgumentException("A failed provider call needs a reason");
		}
		// Keeps the provider's feature order (Map.copyOf would not), so responses are deterministic.
		values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	public static ProviderResult success(Map<String, Double> values, long simulatedLatencyMs) {
		return new ProviderResult(true, values, simulatedLatencyMs, null);
	}

	public static ProviderResult failure(String reason, long simulatedLatencyMs) {
		return new ProviderResult(false, Map.of(), simulatedLatencyMs, reason);
	}

}
