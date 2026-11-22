package edu.auk.acquisition.session;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import edu.auk.acquisition.feature.ObservedFeatures;
import edu.auk.acquisition.policy.DecisionDetails;

/**
 * Current state of one acquisition session. Holds only what has been observed: the initial
 * features plus the groups of successfully acquired providers (FR-5).
 *
 * @param stopExplanation        why the policy stopped, in words; {@code null} while active
 * @param stopDecision           the details of the stop decision; {@code null} while active
 * @param accumulatedSimulatedMs sum of simulated provider latencies (virtual time)
 * @param executionMs            real processing time spent in the backend
 */
public record AcquisitionSession(UUID id, String recordId, String datasetSplit, String accessMode, String policyId,
		String scenarioVersion, String featureConfigVersion, String modelVersion, SessionStatus status, String stopReason,
		String stopExplanation, DecisionDetails stopDecision, ObservedFeatures observed, BigDecimal totalCost,
		long accumulatedSimulatedMs, long executionMs, Instant createdAt, Instant updatedAt) {

	/** Successfully acquired providers, in feature-configuration order. */
	public List<String> acquiredProviders(List<String> providerOrder) {
		return providerOrder.stream().filter(observed.acquiredProviders()::contains).toList();
	}

}
