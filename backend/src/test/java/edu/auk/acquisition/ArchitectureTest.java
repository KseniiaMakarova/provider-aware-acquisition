package edu.auk.acquisition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import edu.auk.acquisition.dataset.RestrictedRecordReader;
import edu.auk.acquisition.provider.InformationProvider;
import edu.auk.acquisition.provider.ProviderExecutor;
import edu.auk.acquisition.session.InitialInformationLoader;

/**
 * Enforces the data-visibility boundary: complete records are reachable only through the
 * restricted reader, which only the initial information loader and the simulated providers may
 * use. Rules about packages that do not exist yet (policy, api) are allowed to be empty so they
 * already apply when those packages are added.
 */
@AnalyzeClasses(packages = "edu.auk.acquisition", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	@ArchTest
	static final ArchRule onlyTheInitialLoaderAndSimulatedProvidersReadCompleteRecords = noClasses().that()
		.resideOutsideOfPackages("..dataset..", "..provider.simulated..")
		.and()
		.doNotBelongToAnyOf(InitialInformationLoader.class)
		.should()
		.dependOnClassesThat()
		.areAssignableTo(RestrictedRecordReader.class)
		.because("hidden feature values must only leave the dataset through the initial loader or a provider call");

	@ArchTest
	static final ArchRule policiesSeeOnlyMetadataAndObservedState = noClasses().that()
		.resideInAPackage("..policy..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("..dataset..", "..provider.simulated..", "..persistence..")
		.orShould()
		.dependOnClassesThat()
		.areAssignableTo(ProviderExecutor.class)
		.orShould()
		.dependOnClassesThat()
		.areAssignableTo(InformationProvider.class)
		.allowEmptyShould(true)
		.because("a policy decides from observed values and provider metadata only, and never calls providers");

	@ArchTest
	static final ArchRule predictionUsesOnlyObservedFeatures = noClasses().that()
		.resideInAPackage("..prediction..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("..dataset..", "..provider..", "..scenario..", "..session..");

	@ArchTest
	static final ArchRule apiDoesNotReachDatasetOrProviders = noClasses().that()
		.resideInAPackage("..api..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("..dataset..", "..provider.simulated..")
		.allowEmptyShould(true);

	@ArchTest
	static final ArchRule featureModelIsIndependent = noClasses().that()
		.resideInAPackage("..feature..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("..dataset..", "..provider..", "..prediction..", "..scenario..", "..session..");

	@ArchTest
	static final ArchRule noPackageCycles = slices().matching("edu.auk.acquisition.(*)..").should().beFreeOfCycles();

}
