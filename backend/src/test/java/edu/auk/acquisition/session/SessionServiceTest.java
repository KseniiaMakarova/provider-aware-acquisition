package edu.auk.acquisition.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.TestDataset;
import edu.auk.acquisition.TestScenarios;
import edu.auk.acquisition.dataset.DatasetAccessMode;
import edu.auk.acquisition.dataset.RestrictedRecordReader;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ObservedFeatures;
import edu.auk.acquisition.policy.FixedOrderPolicy;
import edu.auk.acquisition.policy.PolicyRegistry;
import edu.auk.acquisition.prediction.Prediction;
import edu.auk.acquisition.prediction.PredictionClient;
import edu.auk.acquisition.prediction.PredictionUnavailableException;
import edu.auk.acquisition.provider.InformationProvider;
import edu.auk.acquisition.provider.ProviderRegistry;
import edu.auk.acquisition.provider.simulated.SimulatedProvider;
import edu.auk.acquisition.session.SessionExceptions.SessionNotFoundException;
import edu.auk.acquisition.session.SessionExceptions.UnknownRecordException;
import edu.auk.acquisition.session.SessionExceptions.UnsupportedPolicyException;

class SessionServiceTest {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	static final RestrictedRecordReader reader = RestrictedRecordReader.load(TestDataset.FEATURES_DIR,
			DatasetAccessMode.DEVELOPMENT, config);

	static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

	final List<ObservedFeatures> predictionRequests = new ArrayList<>();

	final Map<UUID, SessionHistory> stored = new HashMap<>();

	boolean mlAvailable = true;

	final PredictionClient predictionClient = observed -> {
		predictionRequests.add(observed);
		if (!mlAvailable) {
			throw new PredictionUnavailableException("down");
		}
		return new Prediction(0.37, "mask-hgb-test", config.version());
	};

	final SessionRepository repository = new SessionRepository() {

		@Override
		public void insertNew(AcquisitionSession session, PredictionSnapshot initial) {
			stored.put(session.id(), new SessionHistory(session, List.of(initial), List.of()));
		}

		@Override
		public Optional<SessionHistory> find(UUID id) {
			return Optional.ofNullable(stored.get(id));
		}

		@Override
		public boolean claim(UUID sessionId, UUID token, Instant now, Instant staleBefore) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void release(UUID sessionId, UUID token) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void saveAdvance(UUID token, AcquisitionSession updated, AcquisitionStep step,
				PredictionSnapshot prediction) {
			throw new UnsupportedOperationException();
		}

	};

	final SessionService service = new SessionService(new InitialInformationLoader(reader, config), predictionClient,
			repository, registry(), new PolicyRegistry(List.of(new FixedOrderPolicy(config.providerIds()))), config,
			Clock.fixed(NOW, ZoneOffset.UTC));

	static ProviderRegistry registry() {
		var scenario = TestScenarios.neutral();
		List<InformationProvider> providers = config.providers()
			.stream()
			.<InformationProvider>map(g -> new SimulatedProvider(g, reader, scenario))
			.toList();
		return new ProviderRegistry(config, scenario, providers);
	}

	@Test
	void newSessionObservesOnlyTheInitialFeaturesAndStoresTheInitialPrediction() {
		SessionHistory history = service.create("101", "FIXED_ORDER");
		AcquisitionSession session = history.session();

		assertThat(session.observed().mask()).isEqualTo(config.initialFeatures());
		assertThat(session.observed().values()).containsEntry("LIMIT_BAL", 1001.0).hasSize(3);
		assertThat(session.status()).isEqualTo(SessionStatus.ACTIVE);
		assertThat(session.datasetSplit()).isEqualTo("validation");
		assertThat(session.accessMode()).isEqualTo("DEVELOPMENT");
		assertThat(session.scenarioVersion()).isEqualTo("test-scenario");
		assertThat(session.featureConfigVersion()).isEqualTo(config.version());
		assertThat(session.modelVersion()).isEqualTo("mask-hgb-test");
		assertThat(session.totalCost()).isZero();
		assertThat(session.accumulatedSimulatedMs()).isZero();
		assertThat(session.createdAt()).isEqualTo(NOW);

		PredictionSnapshot initial = history.latestPrediction();
		assertThat(initial.sequenceNumber()).isZero();
		assertThat(initial.probability()).isEqualTo(0.37);
		assertThat(initial.observedMask()).isEqualTo(config.initialFeatures());
		assertThat(stored).containsKey(session.id());
	}

	@Test
	void theModelReceivesOnlyTheInitialFeatures() {
		service.create("101", "FIXED_ORDER");

		assertThat(predictionRequests).singleElement()
			.satisfies(observed -> assertThat(observed.mask()).isEqualTo(config.initialFeatures()));
	}

	@Test
	void allProvidersRemainAtTheStart() {
		SessionHistory history = service.create("101", "FIXED_ORDER");
		assertThat(service.remainingProviders(history)).isEqualTo(config.providerIds());
	}

	@Test
	void recordOutsideTheServedSplitIsRejectedAndNothingIsStored() {
		assertThatThrownBy(() -> service.create("201", "FIXED_ORDER")).isInstanceOf(UnknownRecordException.class)
			.hasMessageContaining("validation");
		assertThat(predictionRequests).isEmpty();
		assertThat(stored).isEmpty();
	}

	@Test
	void mlOutageStoresNothing() {
		mlAvailable = false;
		assertThatThrownBy(() -> service.create("101", "FIXED_ORDER"))
			.isInstanceOf(PredictionUnavailableException.class);
		assertThat(stored).isEmpty();
	}

	@Test
	void unsupportedPolicyIsRejectedBeforeAnythingElse() {
		assertThatThrownBy(() -> service.create("101", "ADAPTIVE")).isInstanceOf(UnsupportedPolicyException.class)
			.hasMessageContaining("FIXED_ORDER");
		assertThat(predictionRequests).isEmpty();
	}

	@Test
	void unknownSessionIsReported() {
		assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(SessionNotFoundException.class);
	}

}
