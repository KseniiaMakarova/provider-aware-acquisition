package edu.auk.acquisition.session;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for sessions; implemented in the persistence package. */
public interface SessionRepository {

	/** Stores a new session together with its initial prediction, atomically. */
	void insertNew(AcquisitionSession session, PredictionSnapshot initialPrediction);

	Optional<SessionHistory> find(UUID sessionId);

	/**
	 * Atomically claims an active session for one advance. Succeeds only if the session is
	 * active and not claimed, or its previous claim is older than {@code staleBefore}.
	 */
	boolean claim(UUID sessionId, UUID token, Instant now, Instant staleBefore);

	/** Releases a claim without changes; does nothing if the token no longer holds it. */
	void release(UUID sessionId, UUID token);

	/**
	 * Saves the outcome of one advance and releases the claim in one transaction.
	 *
	 * @param step       the provider attempt, or {@code null} when the policy stopped
	 * @param prediction the new prediction, or {@code null} when no new information arrived
	 * @throws SessionExceptions.ClaimLostException if the token no longer holds the claim; nothing is saved
	 */
	void saveAdvance(UUID token, AcquisitionSession updated, AcquisitionStep step, PredictionSnapshot prediction);

}
