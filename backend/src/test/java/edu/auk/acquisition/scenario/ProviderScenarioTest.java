package edu.auk.acquisition.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.TestScenarios;

class ProviderScenarioTest {

	static final JsonMapper MAPPER = JsonMapper.builder().build();

	@TempDir
	Path tmp;

	@Test
	void defaultScenarioIsNeutralAndCoversEveryProvider() {
		ProviderScenario scenario = ProviderScenario.load(TestScenarios.DEFAULT_FILE, SharedFeatureConfig.CONFIG);

		assertThat(scenario.version()).isEqualTo("default-v1");
		assertThat(scenario.providers()).containsOnlyKeys(SharedFeatureConfig.CONFIG.providerIds());
		assertThat(scenario.providers().values()).allSatisfy(s -> {
			assertThat(s.cost()).isEqualByComparingTo(BigDecimal.ONE);
			assertThat(s.latencyMs()).isEqualTo(200);
			assertThat(s.available()).isTrue();
			assertThat(s.failureRate()).isZero();
		});
	}

	static Stream<Arguments> invalidScenarios() {
		return Stream.of(
				Arguments.of("missing provider", (Consumer<ObjectNode>) root -> providers(root).remove("BILLING_HISTORY"),
						"missing [BILLING_HISTORY]"),
				Arguments.of("unknown provider",
						(Consumer<ObjectNode>) root -> providers(root).set("CREDIT_BUREAU",
								providers(root).get("BILLING_HISTORY")),
						"unknown [CREDIT_BUREAU]"),
				Arguments.of("negative cost",
						(Consumer<ObjectNode>) root -> provider(root, "PAYMENT_HISTORY").put("cost", -1), "cost"),
				Arguments.of("negative latency",
						(Consumer<ObjectNode>) root -> provider(root, "PAYMENT_HISTORY").put("latencyMs", -5),
						"latencyMs"),
				Arguments.of("failure rate above one",
						(Consumer<ObjectNode>) root -> provider(root, "PAYMENT_HISTORY").put("failureRate", 1.5),
						"failureRate"),
				Arguments.of("availability missing",
						(Consumer<ObjectNode>) root -> provider(root, "PAYMENT_HISTORY").remove("available"), "available"),
				Arguments.of("version missing", (Consumer<ObjectNode>) root -> root.remove("scenarioVersion"),
						"scenarioVersion"),
				Arguments.of("seed missing", (Consumer<ObjectNode>) root -> root.remove("seed"), "seed"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidScenarios")
	void invalidScenarioIsRejected(String name, Consumer<ObjectNode> change, String message) throws Exception {
		ObjectNode root = (ObjectNode) MAPPER.readTree(Files.readString(TestScenarios.DEFAULT_FILE));
		change.accept(root);
		Path file = tmp.resolve("scenario.json");
		Files.writeString(file, MAPPER.writeValueAsString(root));

		assertThatThrownBy(() -> ProviderScenario.load(file, SharedFeatureConfig.CONFIG))
			.isInstanceOf(ScenarioException.class)
			.hasMessageContaining(message);
	}

	@Test
	void unknownProviderSettingsAreRefused() {
		assertThatThrownBy(() -> TestScenarios.neutral().settings("CREDIT_BUREAU"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	static ObjectNode providers(ObjectNode root) {
		return (ObjectNode) root.get("providers");
	}

	static ObjectNode provider(ObjectNode root, String id) {
		return (ObjectNode) providers(root).get(id);
	}

}
