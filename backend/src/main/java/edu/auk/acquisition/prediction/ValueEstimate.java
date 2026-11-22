package edu.auk.acquisition.prediction;

import java.util.List;
import java.util.Optional;

/**
 * Expected value of acquiring each candidate provider, estimated by the ML service from the
 * currently observed state only.
 *
 * @param method             estimation method, e.g. {@code knn-eig-v1}
 * @param sampleSize         number of reference records used (K)
 * @param referenceVersion   version of the training-derived reference sample
 * @param currentProbability the model's prediction for the current observed state
 */
public record ValueEstimate(String method, int sampleSize, String referenceVersion, String modelVersion,
		double currentProbability, List<ProviderValue> values) {

	public ValueEstimate {
		values = List.copyOf(values);
	}

	public Optional<ProviderValue> forProvider(String providerId) {
		return values.stream().filter(v -> v.providerId().equals(providerId)).findFirst();
	}

	/**
	 * @param expectedInformationGainBits expected information gain about the outcome, in bits
	 * @param expectedProbability         mean of the predictions under sampled values of the group
	 * @param expectedAbsoluteChange      mean absolute change from the current probability
	 */
	public record ProviderValue(String providerId, double expectedInformationGainBits, double expectedProbability,
			double expectedAbsoluteChange) {
	}

}
