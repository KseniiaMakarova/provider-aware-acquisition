package edu.auk.acquisition.prediction;

/**
 * The ML service refused the request (HTTP 422). Retrying the same request will not help; it
 * points to a mismatch between the backend and the service.
 */
public final class PredictionRejectedException extends PredictionException {

	private final String errorCode;

	public PredictionRejectedException(String errorCode, String message) {
		super("ML service rejected the observation (" + errorCode + "): " + message, null);
		this.errorCode = errorCode;
	}

	public String errorCode() {
		return errorCode;
	}

}
