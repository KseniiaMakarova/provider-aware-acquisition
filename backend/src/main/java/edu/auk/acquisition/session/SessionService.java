package edu.auk.acquisition.session;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import edu.auk.acquisition.dataset.RecordNotAvailableException;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ObservedFeatures;
import edu.auk.acquisition.policy.PolicyRegistry;
import edu.auk.acquisition.prediction.Prediction;
import edu.auk.acquisition.prediction.PredictionClient;
import edu.auk.acquisition.provider.ProviderMetadata;
import edu.auk.acquisition.provider.ProviderRegistry;
import edu.auk.acquisition.scenario.ProviderScenario;
import edu.auk.acquisition.scenario.ScenarioCatalog;
import edu.auk.acquisition.session.SessionExceptions.SessionNotFoundException;
import edu.auk.acquisition.session.SessionExceptions.UnknownRecordException;
import edu.auk.acquisition.session.SessionExceptions.UnsupportedPolicyException;
import edu.auk.acquisition.session.SessionExceptions.UnsupportedScenarioException;

/** Creates acquisition sessions and reads their history. */
@Service
public class SessionService {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);

	private final InitialInformationLoader initialLoader;

	private final PredictionClient predictionClient;

	private final SessionRepository repository;

	private final ProviderRegistry providers;

	private final ScenarioCatalog scenarios;

	private final PolicyRegistry policies;

	private final FeatureConfiguration config;

	private final Clock clock;

	public SessionService(InitialInformationLoader initialLoader, PredictionClient predictionClient,
			SessionRepository repository, ProviderRegistry providers, ScenarioCatalog scenarios, PolicyRegistry policies,
			FeatureConfiguration config, Clock clock) {
		this.initialLoader = initialLoader;
		this.predictionClient = predictionClient;
		this.repository = repository;
		this.providers = providers;
		this.scenarios = scenarios;
		this.policies = policies;
		this.config = config;
		this.clock = clock;
	}

	/**
	 * Starts a session from the initial information only and stores it with its initial
	 * prediction and a snapshot of its scenario. Nothing is stored if the record is unknown or no
	 * prediction can be obtained.
	 *
	 * @param scenarioId scenario version, or {@code null} for the default scenario
	 * @throws UnsupportedPolicyException   if the policy is not available
	 * @throws UnsupportedScenarioException if the scenario is not configured
	 * @throws UnknownRecordException       if the record is not in the served split
	 * @throws edu.auk.acquisition.prediction.PredictionException if the ML service fails
	 */
	public SessionHistory create(String recordId, String policyId, String scenarioId) {
		long start = System.nanoTime();
		if (policies.find(policyId).isEmpty()) {
			throw new UnsupportedPolicyException(policyId, policies.ids());
		}
		ProviderScenario scenario = scenarioId == null ? scenarios.defaultScenario()
				: scenarios.find(scenarioId)
					.orElseThrow(() -> new UnsupportedScenarioException(scenarioId,
							scenarios.all().stream().map(ProviderScenario::version).toList()));
		ObservedFeatures observed;
		try {
			observed = initialLoader.load(recordId);
		}
		catch (RecordNotAvailableException e) {
			throw new UnknownRecordException(recordId, initialLoader.split());
		}
		// The ML call happens before any database write, so a failure leaves no partial session.
		Prediction prediction = predictionClient.predict(observed);
		Instant now = clock.instant();
		long executionMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
		AcquisitionSession session = new AcquisitionSession(UUID.randomUUID(), recordId, initialLoader.split(),
				initialLoader.accessMode(), policyId, scenario.version(), scenario, config.version(),
				prediction.modelVersion(), SessionStatus.ACTIVE, null, null, null, observed, BigDecimal.ZERO, 0, executionMs,
				now, now);
		PredictionSnapshot initial = new PredictionSnapshot(0, prediction.probability(), observed.mask(),
				prediction.modelVersion(), prediction.featureConfigVersion(), now);
		repository.insertNew(session, initial);
		log.info("Created session {} for record {} with policy {} and scenario {}: initial probability {}", session.id(),
				recordId, policyId, scenario.version(), prediction.probability());
		return new SessionHistory(session, List.of(initial), List.of());
	}

	/** @throws SessionNotFoundException if no such session exists */
	public SessionHistory get(UUID sessionId) {
		return repository.find(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
	}

	/** The scenario the session runs with. */
	public ProviderScenario scenarioOf(SessionHistory history) {
		return scenarios.forSession(history.session().scenarioVersion(), history.session().scenario());
	}

	/** All providers with their characteristics in the session's scenario. */
	public List<ProviderMetadata> providersOf(SessionHistory history) {
		return providers.metadata(scenarioOf(history));
	}

	/** Providers that are still feasible for the session: available and not yet attempted. */
	public List<String> remainingProviders(SessionHistory history) {
		if (history.session().status() != SessionStatus.ACTIVE) {
			return List.of();
		}
		return providers.feasibleProviders(scenarioOf(history), history.attemptedProviders())
			.stream()
			.map(ProviderMetadata::id)
			.toList();
	}

}
