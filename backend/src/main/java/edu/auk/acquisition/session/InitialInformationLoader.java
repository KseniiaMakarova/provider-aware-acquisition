package edu.auk.acquisition.session;

import org.springframework.stereotype.Component;

import edu.auk.acquisition.dataset.RestrictedRecordReader;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ObservedFeatures;

/** Loads the information available before any optional provider is queried (FR-3). */
@Component
public class InitialInformationLoader {

	private final RestrictedRecordReader reader;
	private final FeatureConfiguration config;

	public InitialInformationLoader(RestrictedRecordReader reader, FeatureConfiguration config) {
		this.reader = reader;
		this.config = config;
	}

	/**
	 * @throws edu.auk.acquisition.dataset.RecordNotAvailableException if the record is not in the served split
	 */
	public ObservedFeatures load(String recordId) {
		return ObservedFeatures.of(reader.initialFeatures(recordId), config);
	}

	/** Dataset split whose records sessions are created from. */
	public String split() {
		return reader.split();
	}

	public String accessMode() {
		return reader.accessMode().name();
	}

}
