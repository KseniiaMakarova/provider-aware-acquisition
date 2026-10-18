package edu.auk.acquisition.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import edu.auk.acquisition.config.AcquisitionProperties;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ObservedFeatures;
import edu.auk.acquisition.policy.AcquisitionContext;
import edu.auk.acquisition.policy.AcquisitionDecision;
import edu.auk.acquisition.policy.AcquisitionPolicy;
import edu.auk.acquisition.policy.PolicyRegistry;
import edu.auk.acquisition.prediction.Prediction;
import edu.auk.acquisition.prediction.PredictionClient;
import edu.auk.acquisition.provider.ProviderAttempt;
import edu.auk.acquisition.provider.ProviderContractException;
import edu.auk.acquisition.provider.ProviderExecutor;
import edu.auk.acquisition.provider.ProviderMetadata;
import edu.auk.acquisition.provider.ProviderRegistry;
import edu.auk.acquisition.session.AdvanceResult.Outcome;
import edu.auk.acquisition.session.SessionExceptions.AdvanceInProgressException;
import edu.auk.acquisition.session.SessionExceptions.SessionNotActiveException;
import edu.auk.acquisition.session.SessionExceptions.SessionNotFoundException;

/**
 * Runs one acquisition decision per call (FR-6 to FR-12).
 *
 * <p>Only one advance can run per session: it first claims the session with a short atomic
 * update, then runs the policy, the provider call and the prediction with no database
 * transaction open, and finally saves the step, the prediction and the new session state in one
 * transaction that also releases the claim. If anything fails before saving, the claim is
 * released and the session is unchanged; a crashed request's claim expires after the lease.
 */
@Service
public class AcquisitionOrchestrator {

	private static final Logger log = LoggerFactory.getLogger(AcquisitionOrchestrator.class);

	private final SessionRepository repository;
	private final PolicyRegistry policies;
	private final ProviderRegistry providers;
	private final ProviderExecutor executor;
	private final PredictionClient predictionClient;
	private final FeatureConfiguration config;
	private final Clock clock;
	private final Duration lease;

	public AcquisitionOrchestrator(SessionRepository repository, PolicyRegistry policies, ProviderRegistry providers,
			ProviderExecutor executor, PredictionClient predictionClient, FeatureConfiguration config, Clock clock,
			AcquisitionProperties properties) {
		this.repository = repository;
		this.policies = policies;
		this.providers = providers;
		this.executor = executor;
		this.predictionClient = predictionClient;
		this.config = config;
		this.clock = clock;
		this.lease = properties.advanceLease();
	}

	/**
	 * @throws SessionNotFoundException    if the session does not exist
	 * @throws SessionNotActiveException   if the session has already finished
	 * @throws AdvanceInProgressException  if another advance of the session is running
	 * @throws edu.auk.acquisition.prediction.PredictionException if no prediction can be made; nothing is saved
	 */
	public AdvanceResult advance(UUID sessionId) {
		long start = System.nanoTime();
		ensureActive(find(sessionId));

		UUID token = UUID.randomUUID();
		Instant now = clock.instant();
		if (!repository.claim(sessionId, token, now, now.minus(lease))) {
			ensureActive(find(sessionId));
			throw new AdvanceInProgressException(sessionId);
		}

		boolean saved = false;
		try {
			// Re-read under the claim so the decision uses the latest committed state.
			SessionHistory history = find(sessionId);
			AdvanceResult result = decideAndApply(history, token, start);
			saved = true;
			return result;
		}
		finally {
			if (!saved) {
				repository.release(sessionId, token);
			}
		}
	}

	private AdvanceResult decideAndApply(SessionHistory history, UUID token, long start) {
		AcquisitionSession session = history.session();
		AcquisitionPolicy policy = policies.find(session.policyId())
			.orElseThrow(() -> new IllegalStateException("Session uses unknown policy " + session.policyId()));
		List<String> attempted = history.attemptedProviders();
		List<ProviderMetadata> feasible = providers.feasibleProviders(attempted);
		AcquisitionContext context = new AcquisitionContext(session.observed(), history.latestPrediction().probability(),
				feasible, config.providerIds(), attempted, session.totalCost(), session.accumulatedSimulatedMs());

		AcquisitionDecision decision = policy.selectNext(context);
		return switch (decision) {
			case AcquisitionDecision.Stop stop -> stop(history, token, stop, start);
			case AcquisitionDecision.Acquire acquire -> {
				if (feasible.stream().noneMatch(m -> m.id().equals(acquire.providerId()))) {
					throw new ProviderContractException(
							"Policy " + policy.id() + " selected infeasible provider " + acquire.providerId());
				}
				yield acquire(history, token, acquire, attempted, start);
			}
		};
	}

	private AdvanceResult stop(SessionHistory history, UUID token, AcquisitionDecision.Stop stop, long start) {
		AcquisitionSession s = history.session();
		AcquisitionSession completed = new AcquisitionSession(s.id(), s.recordId(), s.datasetSplit(), s.accessMode(),
				s.policyId(), s.scenarioVersion(), s.featureConfigVersion(), s.modelVersion(), SessionStatus.COMPLETED,
				stop.stopReason().name(), s.observed(), s.totalCost(), s.accumulatedSimulatedMs(),
				s.executionMs() + elapsedMs(start), s.createdAt(), clock.instant());
		repository.saveAdvance(token, completed, null, null);
		log.info("Session {} stopped: {}", s.id(), stop.stopReason());
		return new AdvanceResult(Outcome.STOPPED, stop.reason(), null,
				new SessionHistory(completed, history.predictions(), history.steps()));
	}

	private AdvanceResult acquire(SessionHistory history, UUID token, AcquisitionDecision.Acquire acquire,
			List<String> attempted, long start) {
		AcquisitionSession s = history.session();
		ProviderAttempt attempt = executor.execute(acquire.providerId(), s.recordId(), attempted);
		int stepNumber = history.steps().size() + 1;

		ObservedFeatures observed = s.observed();
		String modelVersion = s.modelVersion();
		PredictionSnapshot snapshot = null;
		if (attempt.succeeded()) {
			Map<String, Double> values = new LinkedHashMap<>(observed.values());
			values.putAll(attempt.values());
			observed = ObservedFeatures.of(values, config);
			Prediction prediction = predictionClient.predict(observed);
			modelVersion = prediction.modelVersion();
			snapshot = new PredictionSnapshot(history.predictions().size(), prediction.probability(), observed.mask(),
					prediction.modelVersion(), prediction.featureConfigVersion(), clock.instant());
		}

		long executionMs = elapsedMs(start);
		Instant now = clock.instant();
		AcquisitionStep step = new AcquisitionStep(stepNumber, attempt.providerId(), acquire.reason(),
				attempt.status().name(), attempt.failureReason(), attempt.succeeded() ? attempt.values() : null,
				attempt.configuredCost(), attempt.chargedCost(), attempt.simulatedLatencyMs(), executionMs,
				snapshot == null ? null : snapshot.sequenceNumber(), now);
		AcquisitionSession updated = new AcquisitionSession(s.id(), s.recordId(), s.datasetSplit(), s.accessMode(),
				s.policyId(), s.scenarioVersion(), s.featureConfigVersion(), modelVersion, SessionStatus.ACTIVE, null,
				observed, s.totalCost().add(attempt.chargedCost()), s.accumulatedSimulatedMs() + attempt.simulatedLatencyMs(),
				s.executionMs() + executionMs, s.createdAt(), now);
		repository.saveAdvance(token, updated, step, snapshot);

		List<AcquisitionStep> steps = new ArrayList<>(history.steps());
		steps.add(step);
		List<PredictionSnapshot> predictions = new ArrayList<>(history.predictions());
		if (snapshot != null) {
			predictions.add(snapshot);
		}
		log.info("Session {} step {}: {} {} (charged {}, simulated {} ms)", s.id(), stepNumber, attempt.providerId(),
				attempt.status(), attempt.chargedCost(), attempt.simulatedLatencyMs());
		return new AdvanceResult(attempt.succeeded() ? Outcome.ACQUIRED : Outcome.PROVIDER_FAILED, acquire.reason(), step,
				new SessionHistory(updated, predictions, steps));
	}

	private SessionHistory find(UUID sessionId) {
		return repository.find(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
	}

	private static void ensureActive(SessionHistory history) {
		AcquisitionSession s = history.session();
		if (s.status() != SessionStatus.ACTIVE) {
			throw new SessionNotActiveException(s.id(), s.status(), s.stopReason());
		}
	}

	private static long elapsedMs(long startNanos) {
		return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
	}

}
