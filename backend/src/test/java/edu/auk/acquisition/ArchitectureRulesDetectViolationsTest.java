package edu.auk.acquisition;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * The architecture rules must not pass just because a package is still empty: each rule is
 * checked against deliberately broken classes from the test sources.
 */
class ArchitectureRulesDetectViolationsTest {

	static final JavaClasses withLeakyFixtures = new ClassFileImporter().importPackages("edu.auk.acquisition");

	@Test
	void readerAccessOutsideTheLoaderAndProvidersIsDetected() {
		assertThatThrownBy(() -> ArchitectureTest.onlyTheInitialLoaderAndSimulatedProvidersReadCompleteRecords
			.check(withLeakyFixtures)).isInstanceOf(AssertionError.class)
			.hasMessageContaining("LeakyPolicyFixture")
			.hasMessageContaining("LeakyPredictionFixture");
	}

	@Test
	void policyReadingRecordsOrCallingProvidersIsDetected() {
		assertThatThrownBy(() -> ArchitectureTest.policiesSeeOnlyMetadataAndObservedState.check(withLeakyFixtures))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("RestrictedRecordReader")
			.hasMessageContaining("ProviderExecutor");
	}

	@Test
	void predictionReadingRecordsIsDetected() {
		assertThatThrownBy(() -> ArchitectureTest.predictionUsesOnlyObservedFeatures.check(withLeakyFixtures))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("LeakyPredictionFixture");
	}

}
