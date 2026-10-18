package edu.auk.acquisition.feature;

/** The feature configuration file is malformed or breaks a grouping rule. */
public class FeatureConfigurationException extends RuntimeException {

	public FeatureConfigurationException(String message) {
		super(message);
	}

	public FeatureConfigurationException(String message, Throwable cause) {
		super(message, cause);
	}

}
