package edu.auk.acquisition.prediction;

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

}
