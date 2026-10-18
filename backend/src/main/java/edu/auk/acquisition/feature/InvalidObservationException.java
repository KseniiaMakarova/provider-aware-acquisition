package edu.auk.acquisition.feature;

/**
 * A set of observed features is not a legal acquisition state: it must contain every initial
 * feature plus whole provider groups, and nothing that is not a model feature.
 */
public class InvalidObservationException extends RuntimeException {

	public InvalidObservationException(String message) {
		super(message);
	}

}
