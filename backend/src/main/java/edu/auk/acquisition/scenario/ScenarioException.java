package edu.auk.acquisition.scenario;

/** The scenario file is malformed or does not match the configured providers. */
public class ScenarioException extends RuntimeException {

	public ScenarioException(String message) {
		super(message);
	}

	public ScenarioException(String message, Throwable cause) {
		super(message, cause);
	}

}
