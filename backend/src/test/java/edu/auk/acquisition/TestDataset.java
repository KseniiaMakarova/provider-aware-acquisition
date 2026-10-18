package edu.auk.acquisition;

import java.nio.file.Path;

/** Small label-free feature files with the real column layout, for tests that need no prepared data. */
public final class TestDataset {

	public static final Path FEATURES_DIR = Path.of("src", "test", "resources", "dataset");

	/** Spring property pointing the backend at the fixture files; shared so test contexts are reused. */
	public static final String FEATURES_DIR_PROPERTY = "acquisition.dataset.features-dir=src/test/resources/dataset";

	private TestDataset() {
	}

}
