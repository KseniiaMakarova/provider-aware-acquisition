package edu.auk.acquisition.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.TestDataset;
import edu.auk.acquisition.TestcontainersConfiguration;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.InvalidObservationException;
import edu.auk.acquisition.feature.ObservedFeatures;
import edu.auk.acquisition.session.AcquisitionSession;
import edu.auk.acquisition.session.PredictionSnapshot;
import edu.auk.acquisition.session.SessionHistory;
import edu.auk.acquisition.session.SessionStatus;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = TestDataset.FEATURES_DIR_PROPERTY)
class JdbcSessionRepositoryTest {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

	@Autowired
	JdbcSessionRepository repository;

	@Autowired
	JdbcTemplate jdbc;

	static AcquisitionSession session(Map<String, Double> values) {
		return new AcquisitionSession(UUID.randomUUID(), "101", "validation", "DEVELOPMENT", "FIXED_ORDER", "default-v1",
				config.version(), "mask-hgb-test", SessionStatus.ACTIVE, null, ObservedFeatures.of(values, config),
				BigDecimal.ZERO, 0, 4, NOW, NOW);
	}

	static Map<String, Double> initialValues() {
		Map<String, Double> values = new LinkedHashMap<>();
		values.put("LIMIT_BAL", 50000.0);
		values.put("BILL_AMT1", 0.0);
		values.put("PAY_AMT1", 2000.0);
		return values;
	}

	@Test
	void sessionAndInitialPredictionRoundTrip() {
		AcquisitionSession session = session(initialValues());
		PredictionSnapshot initial = new PredictionSnapshot(0, 0.2564467723419965, config.initialFeatures(),
				"mask-hgb-test", config.version(), NOW);

		repository.insertNew(session, initial);
		SessionHistory loaded = repository.find(session.id()).orElseThrow();

		// NUMERIC(14,4) returns 0.0000 for 0, so decimals are compared by value.
		assertThat(loaded.session()).usingRecursiveComparison()
			.withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
			.isEqualTo(session);
		assertThat(loaded.session().observed().values()).containsEntry("BILL_AMT1", 0.0);
		assertThat(loaded.predictions()).containsExactly(initial);
		assertThat(loaded.steps()).isEmpty();
	}

	@Test
	void storedValuesAreExactlyTheObservedFeatures() {
		AcquisitionSession session = session(initialValues());
		repository.insertNew(session,
				new PredictionSnapshot(0, 0.3, config.initialFeatures(), "mask-hgb-test", config.version(), NOW));

		String stored = jdbc.queryForObject("SELECT observed_features::text FROM acquisition_session WHERE id = ?",
				String.class, session.id());
		assertThat(stored).contains("LIMIT_BAL", "BILL_AMT1", "PAY_AMT1");
		config.providers().forEach(p -> p.features().forEach(f -> assertThat(stored).doesNotContain(f)));
		assertThat(jdbc.queryForObject("SELECT acquired_providers::text FROM acquisition_session WHERE id = ?",
				String.class, session.id())).isEqualTo("[]");
	}

	@Test
	void unknownSessionIsEmpty() {
		assertThat(repository.find(UUID.randomUUID())).isEmpty();
	}

	@Test
	void corruptedStoredStateIsNotReturnedAsAValidSession() {
		AcquisitionSession session = session(initialValues());
		repository.insertNew(session,
				new PredictionSnapshot(0, 0.3, config.initialFeatures(), "mask-hgb-test", config.version(), NOW));
		jdbc.update("UPDATE acquisition_session SET observed_features = observed_features || '{\"PAY_2\": 0}'::jsonb "
				+ "WHERE id = ?", session.id());

		assertThatThrownBy(() -> repository.find(session.id())).isInstanceOf(InvalidObservationException.class)
			.hasMessageContaining("Partial group");
	}

	@Test
	void acquiredProvidersMustMatchObservedFeatures() {
		AcquisitionSession session = session(initialValues());
		repository.insertNew(session,
				new PredictionSnapshot(0, 0.3, config.initialFeatures(), "mask-hgb-test", config.version(), NOW));
		jdbc.update("UPDATE acquisition_session SET acquired_providers = '[\"BILLING_HISTORY\"]'::jsonb WHERE id = ?",
				session.id());

		assertThatThrownBy(() -> repository.find(session.id())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("do not match");
	}

	@Test
	void masksComeBackInCanonicalOrder() {
		AcquisitionSession session = session(initialValues());
		repository.insertNew(session, new PredictionSnapshot(0, 0.3, List.of("PAY_AMT1", "LIMIT_BAL", "BILL_AMT1"),
				"mask-hgb-test", config.version(), NOW));

		assertThat(repository.find(session.id()).orElseThrow().latestPrediction().observedMask())
			.isEqualTo(config.initialFeatures());
	}

}
