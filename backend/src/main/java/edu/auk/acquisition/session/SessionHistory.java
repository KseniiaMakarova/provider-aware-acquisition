package edu.auk.acquisition.session;

import java.util.List;

/** A session with its predictions and provider attempts, in order. */
public record SessionHistory(AcquisitionSession session, List<PredictionSnapshot> predictions,
		List<AcquisitionStep> steps) {

	public SessionHistory {
		predictions = List.copyOf(predictions);
		steps = List.copyOf(steps);
	}

	public PredictionSnapshot latestPrediction() {
		return predictions.getLast();
	}

	/** Providers attempted so far, successful or not. */
	public List<String> attemptedProviders() {
		return steps.stream().map(AcquisitionStep::providerId).toList();
	}

}
