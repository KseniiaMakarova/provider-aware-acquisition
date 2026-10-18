package edu.auk.acquisition.dataset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatasetConfigTest {

	static final String[] NO_PROFILES = {};

	static final String[] FINAL_PROFILE = { "final-evaluation" };

	@Test
	void developmentWithoutProfileIsAllowed() {
		assertThatCode(() -> DatasetConfig.checkAccessMode(DatasetAccessMode.DEVELOPMENT, NO_PROFILES))
			.doesNotThrowAnyException();
	}

	@Test
	void finalEvaluationWithItsProfileIsAllowed() {
		assertThatCode(() -> DatasetConfig.checkAccessMode(DatasetAccessMode.FINAL_EVALUATION, FINAL_PROFILE))
			.doesNotThrowAnyException();
	}

	@Test
	void finalEvaluationWithoutTheProfileIsRefused() {
		assertThatThrownBy(() -> DatasetConfig.checkAccessMode(DatasetAccessMode.FINAL_EVALUATION, NO_PROFILES))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("requires the 'final-evaluation' Spring profile");
	}

	@Test
	void finalEvaluationProfileWithDevelopmentModeIsRefused() {
		assertThatThrownBy(() -> DatasetConfig.checkAccessMode(DatasetAccessMode.DEVELOPMENT, FINAL_PROFILE))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("requires access mode FINAL_EVALUATION");
	}

}
