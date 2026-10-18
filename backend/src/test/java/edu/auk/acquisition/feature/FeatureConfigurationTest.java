package edu.auk.acquisition.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import edu.auk.acquisition.SharedFeatureConfig;

/** Mirrors ml-service/tests/test_feature_config.py so both sides apply the same rules. */
class FeatureConfigurationTest {

	static final Path SHARED_FILE = SharedFeatureConfig.PATH;

	static final JsonMapper MAPPER = JsonMapper.builder().build();

	static FeatureConfiguration config;

	@TempDir
	Path tmp;

	@BeforeAll
	static void load() {
		config = FeatureConfigurationLoader.load(SHARED_FILE);
	}

	// --- The shared file is the approved grouping ---

	@Test
	void sharedFileIsTheApprovedGrouping() {
		assertThat(config.version()).isEqualTo("fg-2");
		assertThat(config.initialFeatures()).containsExactly("LIMIT_BAL", "BILL_AMT1", "PAY_AMT1");
		assertThat(config.providers()).extracting(ProviderGroup::id)
			.containsExactly("REPAYMENT_HISTORY", "BILLING_HISTORY", "PAYMENT_HISTORY");
		assertThat(config.provider("REPAYMENT_HISTORY").orElseThrow().features())
			.containsExactly("PAY_0", "PAY_2", "PAY_3", "PAY_4", "PAY_5", "PAY_6");
		assertThat(config.modelFeatures()).hasSize(19).doesNotHaveDuplicates();
	}

	@Test
	void demographicsIdAndTargetAreNotModelInputs() {
		assertThat(config.excludedColumns()).containsExactlyInAnyOrder("SEX", "EDUCATION", "MARRIAGE", "AGE");
		assertThat(config.modelFeatures()).doesNotContainAnyElementsOf(config.excludedColumns())
			.doesNotContain(config.recordIdColumn(), config.targetColumn());
	}

	// --- Observation states ---

	@Test
	void everyStateRoundTrips() {
		List<Set<String>> states = config.observationStates();
		assertThat(states).hasSize(1 << config.providers().size()).doesNotHaveDuplicates();
		for (Set<String> state : states) {
			assertThat(config.acquiredProviders(config.observedFeatures(state))).isEqualTo(state);
		}
	}

	@Test
	void initialAndFullStates() {
		assertThat(config.observedFeatures(Set.of())).isEqualTo(config.initialFeatures());
		assertThat(config.observedFeatures(config.providerIds())).isEqualTo(config.modelFeatures());
	}

	static Stream<Arguments> illegalObservations() {
		Function<List<String>, List<String>> withInitial = extra -> {
			List<String> features = new ArrayList<>(config.initialFeatures());
			features.addAll(extra);
			return features;
		};
		return Stream.of(
				Arguments.of("partial group", withInitial.apply(List.of("PAY_2")), "Partial group"),
				Arguments.of("missing initial", config.initialFeatures().subList(1, 3), "Initial features missing"),
				Arguments.of("demographic", withInitial.apply(List.of("AGE")), "not allowed"),
				Arguments.of("target", withInitial.apply(List.of(config.targetColumn())), "not allowed"),
				Arguments.of("record id", withInitial.apply(List.of(config.recordIdColumn())), "not allowed"),
				Arguments.of("unknown", withInitial.apply(List.of("UNKNOWN")), "not allowed"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("illegalObservations")
	void illegalObservationsAreRejected(String name, List<String> observed, String message) {
		assertThatThrownBy(() -> config.acquiredProviders(observed)).isInstanceOf(InvalidObservationException.class)
			.hasMessageContaining(message);
	}

	// --- Invalid files are rejected at load time ---

	static Stream<Arguments> invalidConfigurations() {
		return Stream.of(
				Arguments.of("overlapping groups", (Consumer<ObjectNode>) root -> features(root, 1).add("PAY_2"),
						"more than once"),
				Arguments.of("initial feature inside group",
						(Consumer<ObjectNode>) root -> features(root, 0).add(root.get("initialFeatures").get(0).asString()),
						"more than once"),
				Arguments.of("demographic as feature", (Consumer<ObjectNode>) root -> features(root, 2).add("AGE"),
						"used as features"),
				Arguments.of("target as feature",
						(Consumer<ObjectNode>) root -> features(root, 2).add("default payment next month"),
						"used as features"),
				Arguments.of("duplicate provider id",
						(Consumer<ObjectNode>) root -> ((ObjectNode) root.get("providers").get(1)).put("id",
								root.get("providers").get(0).get("id").asString()),
						"Duplicate provider"),
				Arguments.of("missing initial features", (Consumer<ObjectNode>) root -> root.remove("initialFeatures"),
						"initialFeatures must not be empty"),
				Arguments.of("missing providers", (Consumer<ObjectNode>) root -> root.remove("providers"),
						"Malformed"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidConfigurations")
	void invalidConfigurationIsRejected(String name, Consumer<ObjectNode> change, String message) throws Exception {
		ObjectNode root = (ObjectNode) MAPPER.readTree(Files.readString(SHARED_FILE));
		change.accept(root);
		Path file = tmp.resolve("feature-groups.json");
		Files.writeString(file, MAPPER.writeValueAsString(root));

		assertThatThrownBy(() -> FeatureConfigurationLoader.load(file)).isInstanceOf(FeatureConfigurationException.class)
			.hasMessageContaining(message);
	}

	@Test
	void unreadableFileIsReported() {
		assertThatThrownBy(() -> FeatureConfigurationLoader.load(tmp.resolve("missing.json")))
			.isInstanceOf(FeatureConfigurationException.class)
			.hasMessageContaining("Cannot read");
	}

	@Test
	void archivedFg1GroupingHasTheSameFeatures() {
		FeatureConfiguration fg1 = FeatureConfigurationLoader.load(Path.of("..", "config", "archive", "feature-groups-fg-1.json"));
		assertThat(fg1.version()).isEqualTo("fg-1");
		assertThat(new HashSet<>(fg1.modelFeatures())).isEqualTo(new HashSet<>(config.modelFeatures()));
		assertThat(Map.of("ids", fg1.providerIds())).isEqualTo(Map.of("ids", config.providerIds()));
	}

	private static ArrayNode features(ObjectNode root, int providerIndex) {
		return (ArrayNode) root.get("providers").get(providerIndex).get("features");
	}

}
