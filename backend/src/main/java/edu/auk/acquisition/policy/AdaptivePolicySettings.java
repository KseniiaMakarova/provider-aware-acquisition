package edu.auk.acquisition.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Versioned parameters of the adaptive policy, read from a configuration file. The weights turn
 * cost and latency into bits, so they can be subtracted from the expected information gain. They
 * are provisional experimental parameters, not calibrated or optimal values.
 *
 * @param sampleSize                 reference records per value estimate (K)
 * @param costWeightBitsPerUnit      λ_cost: bits one experimental cost unit is worth
 * @param latencyWeightBitsPerSecond λ_time: bits one second of simulated latency is worth
 */
public record AdaptivePolicySettings(String version, String description, int sampleSize,
		double costWeightBitsPerUnit, double latencyWeightBitsPerSecond) {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	public AdaptivePolicySettings {
		if (version == null || version.isBlank()) {
			throw new IllegalArgumentException("Adaptive policy settings need a policyVersion");
		}
		if (sampleSize < 1 || sampleSize > 1000) {
			throw new IllegalArgumentException("sampleSize must be between 1 and 1000, was " + sampleSize);
		}
		if (!(costWeightBitsPerUnit >= 0 && Double.isFinite(costWeightBitsPerUnit))
				|| !(latencyWeightBitsPerSecond >= 0 && Double.isFinite(latencyWeightBitsPerSecond))) {
			throw new IllegalArgumentException("Cost and latency weights must be zero or positive numbers");
		}
	}

	public static AdaptivePolicySettings load(Path path) {
		RawSettings raw;
		try {
			raw = MAPPER.readValue(Files.readString(path), RawSettings.class);
		}
		catch (IOException | JacksonException e) {
			throw new IllegalArgumentException("Cannot read adaptive policy settings " + path.toAbsolutePath(), e);
		}
		if (raw.sampleSize() == null || raw.costWeightBitsPerUnit() == null || raw.latencyWeightBitsPerSecond() == null) {
			throw new IllegalArgumentException(
					"Adaptive policy settings need sampleSize, costWeightBitsPerUnit and latencyWeightBitsPerSecond");
		}
		return new AdaptivePolicySettings(raw.policyVersion(), raw.description(), raw.sampleSize(),
				raw.costWeightBitsPerUnit(), raw.latencyWeightBitsPerSecond());
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawSettings(String policyVersion, String description, Integer sampleSize, Double costWeightBitsPerUnit,
			Double latencyWeightBitsPerSecond) {
	}

}
