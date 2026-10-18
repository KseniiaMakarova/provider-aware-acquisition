package edu.auk.acquisition.session;

import java.time.Instant;
import java.util.List;

/**
 * A prediction made at one point of a session; never overwritten. Sequence 0 is the
 * prediction from the initial information.
 */
public record PredictionSnapshot(int sequenceNumber, double probability, List<String> observedMask, String modelVersion,
		String featureConfigVersion, Instant createdAt) {

	public PredictionSnapshot {
		observedMask = List.copyOf(observedMask);
	}

}
