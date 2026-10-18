package edu.auk.acquisition.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import edu.auk.acquisition.session.AcquisitionSession;
import edu.auk.acquisition.session.AcquisitionStep;
import edu.auk.acquisition.session.PredictionSnapshot;
import edu.auk.acquisition.session.SessionHistory;

/**
 * Public view of a session: only observed values, the explicit mask, predictions and the
 * acquisition history. Hidden provider values and labels are never part of it.
 */
public record SessionResponse(UUID sessionId, String recordId, String status, String policyId, String datasetSplit,
		String scenarioVersion, String featureConfigVersion, String modelVersion, Map<String, Double> observedFeatures,
		List<String> observedMask, List<String> acquiredProviders, List<String> remainingProviders,
		PredictionView latestPrediction, List<PredictionView> predictions, List<StepView> steps, BigDecimal totalCost,
		long accumulatedSimulatedMs, long executionMs, String stopReason, Instant createdAt) {

	public record PredictionView(int sequenceNumber, double probability, List<String> observedMask, String modelVersion,
			Instant createdAt) {

		static PredictionView of(PredictionSnapshot p) {
			return new PredictionView(p.sequenceNumber(), p.probability(), p.observedMask(), p.modelVersion(),
					p.createdAt());
		}

	}

	public record StepView(int stepNumber, String providerId, String status, String decisionReason,
			String failureReason, Map<String, Double> acquiredFeatures, BigDecimal chargedCost, long simulatedLatencyMs,
			Integer predictionSequenceNumber) {

		static StepView of(AcquisitionStep s) {
			return new StepView(s.stepNumber(), s.providerId(), s.status(), s.decisionReason(), s.failureReason(),
					s.returnedFeatures(), s.chargedCost(), s.simulatedLatencyMs(), s.predictionSequenceNumber());
		}

	}

	static SessionResponse of(SessionHistory history, List<String> providerOrder, List<String> remainingProviders) {
		AcquisitionSession s = history.session();
		return new SessionResponse(s.id(), s.recordId(), s.status().name(), s.policyId(), s.datasetSplit(),
				s.scenarioVersion(), s.featureConfigVersion(), s.modelVersion(), s.observed().values(),
				s.observed().mask(), s.acquiredProviders(providerOrder), remainingProviders,
				PredictionView.of(history.latestPrediction()),
				history.predictions().stream().map(PredictionView::of).toList(),
				history.steps().stream().map(StepView::of).toList(), s.totalCost(), s.accumulatedSimulatedMs(),
				s.executionMs(), s.stopReason(), s.createdAt());
	}

}
