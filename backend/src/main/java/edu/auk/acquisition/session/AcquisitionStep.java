package edu.auk.acquisition.session;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** One recorded provider call attempt (FR-12). */
public record AcquisitionStep(int stepNumber, String providerId, String decisionReason, String status,
		String failureReason, Map<String, Double> returnedFeatures, BigDecimal configuredCost, BigDecimal chargedCost,
		long simulatedLatencyMs, long executionMs, Integer predictionSequenceNumber, Instant createdAt) {

}
