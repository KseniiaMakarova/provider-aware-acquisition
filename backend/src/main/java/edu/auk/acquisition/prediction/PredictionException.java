package edu.auk.acquisition.prediction;

/** A prediction could not be obtained. */
public abstract sealed class PredictionException extends RuntimeException
		permits PredictionRejectedException, PredictionUnavailableException {

	protected PredictionException(String message, Throwable cause) {
		super(message, cause);
	}

}
