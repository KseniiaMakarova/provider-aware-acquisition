package edu.auk.acquisition.session;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for sessions; implemented in the persistence package. */
public interface SessionRepository {

	/** Stores a new session together with its initial prediction, atomically. */
	void insertNew(AcquisitionSession session, PredictionSnapshot initialPrediction);

	Optional<SessionHistory> find(UUID sessionId);

}
