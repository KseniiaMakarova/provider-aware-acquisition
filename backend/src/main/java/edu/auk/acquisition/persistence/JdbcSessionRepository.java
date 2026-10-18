package edu.auk.acquisition.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ObservedFeatures;
import edu.auk.acquisition.session.AcquisitionSession;
import edu.auk.acquisition.session.AcquisitionStep;
import edu.auk.acquisition.session.PredictionSnapshot;
import edu.auk.acquisition.session.SessionExceptions;
import edu.auk.acquisition.session.SessionHistory;
import edu.auk.acquisition.session.SessionRepository;
import edu.auk.acquisition.session.SessionStatus;

/** Stores sessions with plain SQL; JSONB columns hold only observed values and masks. */
@Repository
class JdbcSessionRepository implements SessionRepository {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final TypeReference<Map<String, Double>> VALUES = new TypeReference<>() {
	};

	private static final TypeReference<List<String>> NAMES = new TypeReference<>() {
	};

	private final JdbcClient jdbc;
	private final FeatureConfiguration config;

	JdbcSessionRepository(JdbcClient jdbc, FeatureConfiguration config) {
		this.jdbc = jdbc;
		this.config = config;
	}

	@Override
	@Transactional
	public void insertNew(AcquisitionSession s, PredictionSnapshot initial) {
		jdbc.sql("""
				INSERT INTO acquisition_session (id, record_id, dataset_split, access_mode, policy_id, scenario_version,
				    feature_config_version, model_version, status, stop_reason, observed_features, acquired_providers,
				    total_cost, accumulated_simulated_ms, execution_ms, created_at, updated_at)
				VALUES (:id, :recordId, :split, :accessMode, :policyId, :scenario, :featureConfig, :model, :status,
				    :stopReason, CAST(:observed AS jsonb), CAST(:acquired AS jsonb), :totalCost, :simulatedMs,
				    :executionMs, :createdAt, :updatedAt)
				""")
			.param("id", s.id())
			.param("recordId", s.recordId())
			.param("split", s.datasetSplit())
			.param("accessMode", s.accessMode())
			.param("policyId", s.policyId())
			.param("scenario", s.scenarioVersion())
			.param("featureConfig", s.featureConfigVersion())
			.param("model", s.modelVersion())
			.param("status", s.status().name())
			.param("stopReason", s.stopReason())
			.param("observed", JSON.writeValueAsString(s.observed().values()))
			.param("acquired", JSON.writeValueAsString(s.acquiredProviders(config.providerIds())))
			.param("totalCost", s.totalCost())
			.param("simulatedMs", s.accumulatedSimulatedMs())
			.param("executionMs", s.executionMs())
			.param("createdAt", utc(s.createdAt()))
			.param("updatedAt", utc(s.updatedAt()))
			.update();
		insertSnapshot(s.id(), initial);
	}

	@Override
	public boolean claim(UUID sessionId, UUID token, Instant now, Instant staleBefore) {
		return jdbc.sql("""
				UPDATE acquisition_session SET advance_token = :token, advance_claimed_at = :now
				WHERE id = :id AND status = 'ACTIVE'
				  AND (advance_token IS NULL OR advance_claimed_at < :staleBefore)
				""")
			.param("token", token)
			.param("now", utc(now))
			.param("id", sessionId)
			.param("staleBefore", utc(staleBefore))
			.update() == 1;
	}

	@Override
	public void release(UUID sessionId, UUID token) {
		jdbc.sql("""
				UPDATE acquisition_session SET advance_token = NULL, advance_claimed_at = NULL
				WHERE id = :id AND advance_token = :token
				""").param("id", sessionId).param("token", token).update();
	}

	@Override
	@Transactional
	public void saveAdvance(UUID token, AcquisitionSession s, AcquisitionStep step, PredictionSnapshot prediction) {
		int updated = jdbc.sql("""
				UPDATE acquisition_session
				SET status = :status, stop_reason = :stopReason, observed_features = CAST(:observed AS jsonb),
				    acquired_providers = CAST(:acquired AS jsonb), model_version = :model, total_cost = :totalCost,
				    accumulated_simulated_ms = :simulatedMs, execution_ms = :executionMs, updated_at = :updatedAt,
				    advance_token = NULL, advance_claimed_at = NULL, version = version + 1
				WHERE id = :id AND advance_token = :token
				""")
			.param("status", s.status().name())
			.param("stopReason", s.stopReason())
			.param("observed", JSON.writeValueAsString(s.observed().values()))
			.param("acquired", JSON.writeValueAsString(s.acquiredProviders(config.providerIds())))
			.param("model", s.modelVersion())
			.param("totalCost", s.totalCost())
			.param("simulatedMs", s.accumulatedSimulatedMs())
			.param("executionMs", s.executionMs())
			.param("updatedAt", utc(s.updatedAt()))
			.param("id", s.id())
			.param("token", token)
			.update();
		if (updated != 1) {
			throw new SessionExceptions.ClaimLostException(s.id());
		}
		if (step != null) {
			insertStep(s.id(), step);
		}
		if (prediction != null) {
			insertSnapshot(s.id(), prediction);
		}
	}

	private void insertStep(UUID sessionId, AcquisitionStep step) {
		jdbc.sql("""
				INSERT INTO acquisition_step (id, session_id, step_number, provider_id, decision_reason, status,
				    failure_reason, returned_features, configured_cost, charged_cost, simulated_latency_ms, execution_ms,
				    prediction_sequence_number, created_at)
				VALUES (:id, :sessionId, :stepNumber, :providerId, :reason, :status, :failure, CAST(:features AS jsonb),
				    :configuredCost, :chargedCost, :simulatedMs, :executionMs, :sequence, :createdAt)
				""")
			.param("id", UUID.randomUUID())
			.param("sessionId", sessionId)
			.param("stepNumber", step.stepNumber())
			.param("providerId", step.providerId())
			.param("reason", step.decisionReason())
			.param("status", step.status())
			.param("failure", step.failureReason())
			.param("features", step.returnedFeatures() == null ? null : JSON.writeValueAsString(step.returnedFeatures()))
			.param("configuredCost", step.configuredCost())
			.param("chargedCost", step.chargedCost())
			.param("simulatedMs", step.simulatedLatencyMs())
			.param("executionMs", step.executionMs())
			.param("sequence", step.predictionSequenceNumber())
			.param("createdAt", utc(step.createdAt()))
			.update();
	}

	private void insertSnapshot(UUID sessionId, PredictionSnapshot p) {
		jdbc.sql("""
				INSERT INTO prediction_snapshot (id, session_id, sequence_number, probability, observed_mask,
				    model_version, feature_config_version, created_at)
				VALUES (:id, :sessionId, :sequence, :probability, CAST(:mask AS jsonb), :model, :featureConfig, :createdAt)
				""")
			.param("id", UUID.randomUUID())
			.param("sessionId", sessionId)
			.param("sequence", p.sequenceNumber())
			.param("probability", p.probability())
			.param("mask", JSON.writeValueAsString(p.observedMask()))
			.param("model", p.modelVersion())
			.param("featureConfig", p.featureConfigVersion())
			.param("createdAt", utc(p.createdAt()))
			.update();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<SessionHistory> find(UUID sessionId) {
		Optional<AcquisitionSession> session = jdbc.sql("SELECT * FROM acquisition_session WHERE id = :id")
			.param("id", sessionId)
			.query(this::mapSession)
			.optional();
		return session.map(s -> new SessionHistory(s, predictions(sessionId), steps(sessionId)));
	}

	private List<PredictionSnapshot> predictions(UUID sessionId) {
		return jdbc.sql("SELECT * FROM prediction_snapshot WHERE session_id = :id ORDER BY sequence_number")
			.param("id", sessionId)
			.query((rs, n) -> new PredictionSnapshot(rs.getInt("sequence_number"), rs.getDouble("probability"),
					canonicalOrder(JSON.readValue(rs.getString("observed_mask"), NAMES)), rs.getString("model_version"),
					rs.getString("feature_config_version"), instant(rs, "created_at")))
			.list();
	}

	private List<AcquisitionStep> steps(UUID sessionId) {
		return jdbc.sql("SELECT * FROM acquisition_step WHERE session_id = :id ORDER BY step_number")
			.param("id", sessionId)
			.query((rs, n) -> {
				String features = rs.getString("returned_features");
				Integer sequence = (Integer) rs.getObject("prediction_sequence_number");
				return new AcquisitionStep(rs.getInt("step_number"), rs.getString("provider_id"),
						rs.getString("decision_reason"), rs.getString("status"), rs.getString("failure_reason"),
						features == null ? null : canonicalOrder(JSON.readValue(features, VALUES)),
						rs.getBigDecimal("configured_cost"),
						rs.getBigDecimal("charged_cost"), rs.getLong("simulated_latency_ms"), rs.getLong("execution_ms"),
						sequence, instant(rs, "created_at"));
			})
			.list();
	}

	private AcquisitionSession mapSession(ResultSet rs, int rowNum) throws SQLException {
		// Rebuilding ObservedFeatures re-checks that the stored state is a legal observation.
		ObservedFeatures observed = ObservedFeatures.of(JSON.readValue(rs.getString("observed_features"), VALUES),
				config);
		List<String> storedAcquired = JSON.readValue(rs.getString("acquired_providers"), NAMES);
		if (!new HashSet<>(storedAcquired).equals(observed.acquiredProviders())) {
			throw new IllegalStateException("Session " + rs.getObject("id") + " has acquired providers " + storedAcquired
					+ " that do not match its observed features");
		}
		return new AcquisitionSession(rs.getObject("id", UUID.class), rs.getString("record_id"),
				rs.getString("dataset_split"), rs.getString("access_mode"), rs.getString("policy_id"),
				rs.getString("scenario_version"), rs.getString("feature_config_version"), rs.getString("model_version"),
				SessionStatus.valueOf(rs.getString("status")), rs.getString("stop_reason"), observed,
				rs.getBigDecimal("total_cost"), rs.getLong("accumulated_simulated_ms"), rs.getLong("execution_ms"),
				instant(rs, "created_at"), instant(rs, "updated_at"));
	}

	/** JSONB does not keep key order; masks are returned in canonical feature order. */
	private List<String> canonicalOrder(List<String> features) {
		return config.modelFeatures().stream().filter(features::contains).toList();
	}

	private Map<String, Double> canonicalOrder(Map<String, Double> values) {
		Map<String, Double> ordered = new LinkedHashMap<>();
		config.modelFeatures().stream().filter(values::containsKey).forEach(f -> ordered.put(f, values.get(f)));
		return ordered;
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, OffsetDateTime.class).toInstant();
	}

}
