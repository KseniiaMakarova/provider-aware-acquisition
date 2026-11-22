package edu.auk.acquisition.prediction;

import java.util.List;

import edu.auk.acquisition.feature.ObservedFeatures;

/**
 * Port to the ML inference service.
 *
 * <p>Receives only the currently observed features and their mask, never a record identifier,
 * hidden provider values or the target label.
 */
public interface PredictionClient {

	/**
	 * @throws PredictionRejectedException    if the service rejects the observation
	 * @throws PredictionUnavailableException  if the service cannot be reached, times out or returns an
	 *                                         unusable response
	 */
	Prediction predict(ObservedFeatures observed);

	/**
	 * Estimates the expected value of acquiring each candidate provider in the observed state.
	 *
	 * @param candidateProviders providers not acquired yet, in the order the estimates are returned
	 * @param sampleSize         number of reference records the estimate uses (K)
	 * @throws PredictionRejectedException    if the service rejects the observation or candidates
	 * @throws PredictionUnavailableException  if the service cannot be reached, has no value reference,
	 *                                         times out or returns an unusable response
	 */
	ValueEstimate estimateValue(ObservedFeatures observed, List<String> candidateProviders, int sampleSize);

}
