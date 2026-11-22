package edu.auk.acquisition.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.TestScenarios;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ProviderGroup;
import edu.auk.acquisition.provider.ProviderExecutorTest.FakeProvider;
import edu.auk.acquisition.scenario.ProviderScenario;
import edu.auk.acquisition.scenario.ProviderSettings;

class ProviderRegistryTest {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	static List<InformationProvider> fakes() {
		return config.providers().stream().<InformationProvider>map(g -> new FakeProvider(g, false, List.of(), 0)).toList();
	}

	final ProviderRegistry registry = new ProviderRegistry(config, fakes());

	@Test
	void metadataCombinesDefinitionsAndScenarioSettings() {
		ProviderScenario scenario = TestScenarios.neutral(
				Map.of("REPAYMENT_HISTORY", new ProviderSettings(new BigDecimal("3"), 500, true, 0.0)));

		ProviderMetadata repayment = registry.metadata("REPAYMENT_HISTORY", scenario).orElseThrow();
		assertThat(repayment.suppliedFeatures()).isEqualTo(config.provider("REPAYMENT_HISTORY").orElseThrow().features());
		assertThat(repayment.cost()).isEqualByComparingTo("3");
		assertThat(repayment.latencyMs()).isEqualTo(500);
		assertThat(repayment.expectedLatencyMs()).isEqualTo(500);
		assertThat(repayment.successProbability()).isEqualTo(1.0);
		assertThat(registry.metadata(scenario)).extracting(ProviderMetadata::id).containsExactlyElementsOf(config.providerIds());
	}

	@Test
	void feasibleProvidersExcludeUnavailableAndAttemptedOnes() {
		ProviderScenario scenario = TestScenarios.neutral(
				Map.of("BILLING_HISTORY", new ProviderSettings(BigDecimal.ONE, 200, false, 0.0)));

		assertThat(registry.feasibleProviders(scenario, Set.of())).extracting(ProviderMetadata::id)
			.containsExactly("REPAYMENT_HISTORY", "PAYMENT_HISTORY");
		assertThat(registry.feasibleProviders(scenario, Set.of("REPAYMENT_HISTORY"))).extracting(ProviderMetadata::id)
			.containsExactly("PAYMENT_HISTORY");
		assertThat(registry.feasibleProviders(scenario, Set.of("REPAYMENT_HISTORY", "PAYMENT_HISTORY"))).isEmpty();
	}

	@Test
	void changingTheScenarioChangesCostsButNotFeatures() {
		ProviderScenario cheap = TestScenarios.neutral();
		ProviderScenario expensive = TestScenarios.neutral(
				Map.of("PAYMENT_HISTORY", new ProviderSettings(BigDecimal.TEN, 900, true, 0.3)));

		assertThat(registry.metadata("PAYMENT_HISTORY", expensive).orElseThrow().cost()).isEqualByComparingTo("10");
		assertThat(registry.metadata("PAYMENT_HISTORY", expensive).orElseThrow().successProbability()).isEqualTo(0.7);
		assertThat(registry.metadata(expensive)).extracting(ProviderMetadata::suppliedFeatures)
			.isEqualTo(registry.metadata(cheap).stream().map(ProviderMetadata::suppliedFeatures).toList());
	}

	@Test
	void everyConfiguredProviderNeedsExactlyOneMatchingImplementation() {
		List<InformationProvider> missing = new ArrayList<>(fakes());
		missing.remove(0);
		assertThatThrownBy(() -> new ProviderRegistry(config, missing))
			.hasMessageContaining("No implementation");

		List<InformationProvider> duplicate = new ArrayList<>(fakes());
		duplicate.add(fakes().get(0));
		assertThatThrownBy(() -> new ProviderRegistry(config, duplicate))
			.hasMessageContaining("More than one");

		ProviderGroup billing = config.provider("BILLING_HISTORY").orElseThrow();
		List<InformationProvider> wrongFeatures = new ArrayList<>(fakes());
		wrongFeatures.set(1, new FakeProvider(new ProviderGroup(billing.id(), "", List.of("BILL_AMT2")), false,
				List.of(), 0));
		assertThatThrownBy(() -> new ProviderRegistry(config, wrongFeatures))
			.hasMessageContaining("feature configuration declares");
	}

}
