package edu.auk.acquisition.dataset;

/** The feature record file is missing, malformed or not a label-free feature file. */
public class DatasetException extends RuntimeException {

	public DatasetException(String message) {
		super(message);
	}

	public DatasetException(String message, Throwable cause) {
		super(message, cause);
	}

}
