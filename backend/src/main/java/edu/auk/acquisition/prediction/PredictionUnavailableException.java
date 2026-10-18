package edu.auk.acquisition.prediction;

/**
 * The ML service could not be reached, did not answer in time, failed, or returned a response
 * that cannot be used. A later attempt may succeed.
 */
public final class PredictionUnavailableException extends PredictionException {

	public PredictionUnavailableException(String message) {
		super(message, null);
	}

	public PredictionUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
