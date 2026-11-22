package edu.auk.acquisition.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import edu.auk.acquisition.policy.DecisionDetails;
import edu.auk.acquisition.provider.ProviderMetadata;
import edu.auk.acquisition.session.AcquisitionSession;
import edu.auk.acquisition.session.AcquisitionStep;
import edu.auk.acquisition.session.PredictionSnapshot;
import edu.auk.acquisition.session.SessionHistory;

/**
 * Public view of a session: only observed values, the explicit mask, predictions and the
 * acquisition history. Hidden provider values and labels are never part of it.
 */
public record SessionResponse(UUID sessionId, String recordId, String status, String policyId, String datasetSplit,
		String scenarioVersion, List<ProviderView> providers, String featureConfigVersion, String modelVersion,
		Map<String, Double> observedFeatures, List<String> observedMask, List<String> acquiredProviders,
		List<String> remainingProviders, PredictionView latestPrediction, List<PredictionView> predictions,
		List<StepView> steps, BigDecimal totalCost, long accumulatedSimulatedMs, long executionMs, String stopReason,
		String stopExplanation, DecisionDetails stopDecision, Instant createdAt) {

	/**
	 * A provider's characteristics in the session's scenario. Feature names are left out, so the
	 * session view names no feature that has not been acquired; {@code /api/v1/providers} lists them.
	 */
	public record ProviderView(String id, String description, BigDecimal cost, long latencyMs,
			long latencyJitterMs, Long timeoutMs, boolean available, double failureRate, double timeoutProbability,
			double expectedLatencyMs, double successProbability) {

		static ProviderView of(ProviderMetadata p) {
			return new ProviderView(p.id(), p.description(), p.cost(), p.latencyMs(), p.latencyJitterMs(), p.timeoutMs(),
					p.available(), p.failureRate(), p.timeoutProbability(), p.expectedLatencyMs(), p.successProbability());
		}

	}

	public record PredictionView(int sequenceNumber, double probability, List<String> observedMask, String modelVersion,
			Instant createdAt) {

		static PredictionView of(PredictionSnapshot p) {
			return new PredictionView(p.sequenceNumber(), p.probability(), p.observedMask(), p.modelVersion(),
					p.createdAt());
		}

	}

	public record StepView(int stepNumber, String providerId, String status, String decisionReason,
			String failureType, String failureReason, Map<String, Double> acquiredFeatures, BigDecimal chargedCost,
			long simulatedLatencyMs, Integer predictionSequenceNumber, DecisionDetails decisionDetails) {

		static StepView of(AcquisitionStep s) {
			if (s == null) {
				return null;
			}
			return new StepView(s.stepNumber(), s.providerId(), s.status(), s.decisionReason(), s.failureType(),
					s.failureReason(), s.returnedFeatures(), s.chargedCost(), s.simulatedLatencyMs(),
					s.predictionSequenceNumber(), s.decisionDetails());
		}

	}

	static SessionResponse of(SessionHistory history, List<String> providerOrder, List<ProviderMetadata> providers,
			List<String> remainingProviders) {
		AcquisitionSession s = history.session();
		return new SessionResponse(s.id(), s.recordId(), s.status().name(), s.policyId(), s.datasetSplit(),
				s.scenarioVersion(), providers.stream().map(ProviderView::of).toList(), s.featureConfigVersion(),
				s.modelVersion(), s.observed().values(), s.observed().mask(), s.acquiredProviders(providerOrder),
				remainingProviders, PredictionView.of(history.latestPrediction()),
				history.predictions().stream().map(PredictionView::of).toList(),
				history.steps().stream().map(StepView::of).toList(), s.totalCost(), s.accumulatedSimulatedMs(),
				s.executionMs(), s.stopReason(), s.stopExplanation(), s.stopDecision(), s.createdAt());
	}

}
