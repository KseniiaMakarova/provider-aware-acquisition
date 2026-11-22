package edu.auk.acquisition.session;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import edu.auk.acquisition.policy.DecisionDetails;

/**
 * One recorded provider call attempt (FR-12), with the details of the decision that chose it.
 *
 * @param decisionDetails the policy's view of every provider at the decision; {@code null} for
 *                        steps recorded before decision details were stored
 */
public record AcquisitionStep(int stepNumber, String providerId, String decisionReason, String status,
		String failureReason, Map<String, Double> returnedFeatures, BigDecimal configuredCost, BigDecimal chargedCost,
		long simulatedLatencyMs, long executionMs, Integer predictionSequenceNumber, DecisionDetails decisionDetails,
		Instant createdAt) {
}
