package edu.auk.acquisition.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.TestScenarios;

class ScenarioCatalogTest {

	static final Path CONFIGURED = Path.of("..", "config", "scenarios");

	@Test
	void configuredScenariosAreLoadedWithTheDefaultFirst() {
		ScenarioCatalog catalog = ScenarioCatalog.load(CONFIGURED, "default-v1", SharedFeatureConfig.CONFIG);
		assertThat(catalog.all()).extracting(ProviderScenario::version)
			.containsExactly("default-v1", "costly-repayment-v1", "repayment-unavailable-v1", "unreliable-v1");
		assertThat(catalog.defaultScenario().version()).isEqualTo("default-v1");
	}

	@Test
	void demonstrationScenariosChangeOnlyWhatTheyAreAbout() {
		ScenarioCatalog catalog = ScenarioCatalog.load(CONFIGURED, "default-v1", SharedFeatureConfig.CONFIG);
		assertThat(catalog.find("costly-repayment-v1").orElseThrow().settings("REPAYMENT_HISTORY").cost())
			.isEqualByComparingTo("10");
		assertThat(catalog.find("repayment-unavailable-v1").orElseThrow().settings("REPAYMENT_HISTORY").available())
			.isFalse();
		ProviderSettings slow = catalog.find("unreliable-v1").orElseThrow().settings("REPAYMENT_HISTORY");
		assertThat(slow.timeoutMs()).isEqualTo(1000L);
		assertThat(slow.latencyJitterMs()).isEqualTo(800);
		assertThat(slow.timeoutProbability()).isCloseTo(400.0 / 801, within(1e-12));
	}

	@Test
	void anotherDefaultCanBeChosen() {
		assertThat(ScenarioCatalog.load(CONFIGURED, "unreliable-v1", SharedFeatureConfig.CONFIG).all().getFirst().version())
			.isEqualTo("unreliable-v1");
	}

	@Test
	void missingDefaultOrDuplicateVersionsAreRejected() {
		assertThatThrownBy(() -> ScenarioCatalog.load(CONFIGURED, "storm-v9", SharedFeatureConfig.CONFIG))
			.isInstanceOf(ScenarioException.class)
			.hasMessageContaining("storm-v9");
		assertThatThrownBy(() -> new ScenarioCatalog(List.of(TestScenarios.neutral(), TestScenarios.neutral()),
				"test-scenario"))
			.isInstanceOf(ScenarioException.class)
			.hasMessageContaining("More than one");
	}

	@Test
	void aStoredSessionKeepsItsSnapshotEvenIfTheScenarioChanged() {
		ProviderScenario current = TestScenarios.neutral();
		ProviderScenario snapshot = TestScenarios.neutral(
				Map.of("PAYMENT_HISTORY", new ProviderSettings(BigDecimal.TEN, 200, true, 0.0)));
		ScenarioCatalog catalog = new ScenarioCatalog(List.of(current), "test-scenario");
		assertThat(catalog.forSession("test-scenario", snapshot)).isSameAs(snapshot);
		assertThat(catalog.forSession("test-scenario", null)).isSameAs(current);
		assertThatThrownBy(() -> catalog.forSession("old-v0", null)).isInstanceOf(ScenarioException.class);
	}

	@Test
	void snapshotJsonRoundTrips() {
		ProviderScenario scenario = TestScenarios.neutral(
				Map.of("PAYMENT_HISTORY", new ProviderSettings(new BigDecimal("2.5"), 300, true, 0.25, 600L, 400)));
		assertThat(ProviderScenario.fromJson(scenario.toJson())).isEqualTo(scenario);
	}

}
