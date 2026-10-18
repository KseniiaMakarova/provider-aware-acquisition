package edu.auk.acquisition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The V2 migration enforces the acquisition invariants in the database itself. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = TestDataset.FEATURES_DIR_PROPERTY)
class AcquisitionSchemaTests {

	@Autowired
	JdbcTemplate jdbc;

	static Timestamp now() {
		return Timestamp.from(Instant.now());
	}

	UUID insertSession(String split, String status, String stopReason) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO acquisition_session (id, record_id, dataset_split, access_mode, policy_id, scenario_version,
				    feature_config_version, model_version, status, stop_reason, observed_features, created_at, updated_at)
				VALUES (?, '101', ?, 'DEVELOPMENT', 'FIXED_ORDER', 'default-v1', 'fg-2', 'mask-hgb-test', ?, ?,
				    '{"LIMIT_BAL": 1001}'::jsonb, ?, ?)
				""", id, split, status, stopReason, now(), now());
		return id;
	}

	UUID insertActiveSession() {
		return insertSession("validation", "ACTIVE", null);
	}

	void insertSnapshot(UUID session, int sequence, double probability) {
		jdbc.update("""
				INSERT INTO prediction_snapshot (id, session_id, sequence_number, probability, observed_mask,
				    model_version, feature_config_version, created_at)
				VALUES (?, ?, ?, ?, '["LIMIT_BAL"]'::jsonb, 'mask-hgb-test', 'fg-2', ?)
				""", UUID.randomUUID(), session, sequence, probability, now());
	}

	void insertStep(UUID session, int stepNumber, String provider, String status, String features, String failure) {
		jdbc.update("""
				INSERT INTO acquisition_step (id, session_id, step_number, provider_id, status, failure_reason,
				    returned_features, configured_cost, charged_cost, simulated_latency_ms, execution_ms, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 1.0, 1.0, 150, 3, ?)
				""", UUID.randomUUID(), session, stepNumber, provider, status, failure, features, now());
	}

	@Test
	void validSessionHistoryIsAccepted() {
		UUID session = insertActiveSession();
		assertThatCode(() -> {
			insertSnapshot(session, 0, 0.31);
			insertStep(session, 1, "REPAYMENT_HISTORY", "SUCCESS", "{\"PAY_0\": 0}", null);
			insertSnapshot(session, 1, 0.22);
			insertStep(session, 2, "BILLING_HISTORY", "FAILED", null, "simulated failure");
		}).doesNotThrowAnyException();
	}

	@Test
	void trainingRecordsCannotBeSessionRecords() {
		assertThatThrownBy(() -> insertSession("train", "ACTIVE", null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void predictionsAreNeverOverwritten() {
		UUID session = insertActiveSession();
		insertSnapshot(session, 0, 0.31);
		assertThatThrownBy(() -> insertSnapshot(session, 0, 0.40)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void probabilityMustBeBetweenZeroAndOne() {
		UUID session = insertActiveSession();
		assertThatThrownBy(() -> insertSnapshot(session, 0, 1.5)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aProviderIsAttemptedAtMostOncePerSession() {
		UUID session = insertActiveSession();
		insertStep(session, 1, "BILLING_HISTORY", "FAILED", null, "simulated failure");
		assertThatThrownBy(() -> insertStep(session, 2, "BILLING_HISTORY", "SUCCESS", "{\"BILL_AMT2\": 1}", null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void stepNumbersAreUniquePerSession() {
		UUID session = insertActiveSession();
		insertStep(session, 1, "BILLING_HISTORY", "FAILED", null, "simulated failure");
		assertThatThrownBy(() -> insertStep(session, 1, "PAYMENT_HISTORY", "FAILED", null, "simulated failure"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void successNeedsFeaturesAndFailureNeedsAReason() {
		UUID session = insertActiveSession();
		assertThatThrownBy(() -> insertStep(session, 1, "BILLING_HISTORY", "SUCCESS", null, null))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertStep(session, 1, "BILLING_HISTORY", "FAILED", "{\"BILL_AMT2\": 1}", "x"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void finishedSessionNeedsAStopReason() {
		assertThatThrownBy(() -> insertSession("validation", "COMPLETED", null))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatCode(() -> insertSession("validation", "COMPLETED", "ALL_PROVIDERS_ACQUIRED"))
			.doesNotThrowAnyException();
	}

	@Test
	void advanceClaimFieldsAreSetTogether() {
		UUID session = insertActiveSession();
		assertThatThrownBy(() -> jdbc.update("UPDATE acquisition_session SET advance_token = ? WHERE id = ?",
				UUID.randomUUID(), session))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deletingASessionRemovesItsHistory() {
		UUID session = insertActiveSession();
		insertSnapshot(session, 0, 0.31);
		insertStep(session, 1, "BILLING_HISTORY", "FAILED", null, "simulated failure");

		jdbc.update("DELETE FROM acquisition_session WHERE id = ?", session);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM prediction_snapshot WHERE session_id = ?", Integer.class,
				session)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM acquisition_step WHERE session_id = ?", Integer.class,
				session)).isZero();
	}

	@Test
	void sessionTablesHaveNoColumnForLabelsOrWholeRecords() {
		var columns = jdbc.queryForList("""
				SELECT column_name FROM information_schema.columns
				WHERE table_name IN ('acquisition_session', 'acquisition_step', 'prediction_snapshot')
				""", String.class);
		assertThat(columns).noneMatch(c -> c.contains("label") || c.contains("target") || c.contains("default"));
	}

}
