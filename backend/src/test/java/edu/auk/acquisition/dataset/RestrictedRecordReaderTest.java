package edu.auk.acquisition.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.TestDataset;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ProviderGroup;

class RestrictedRecordReaderTest {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	static final RestrictedRecordReader development = RestrictedRecordReader.load(TestDataset.FEATURES_DIR,
			DatasetAccessMode.DEVELOPMENT, config);

	@TempDir
	Path tmp;

	/** Fixture record 101 holds 1000 + (1-based position of the feature in the model feature list). */
	static double fixtureValue(String feature) {
		return 1001 + config.modelFeatures().indexOf(feature);
	}

	// --- Access mode selects the split ---

	@Test
	void developmentModeServesOnlyValidationRecords() {
		assertThat(development.split()).isEqualTo("validation");
		assertThat(development.size()).isEqualTo(2);
		assertThat(development.contains("101")).isTrue();
		assertThat(development.contains("201")).isFalse();
	}

	@Test
	void finalEvaluationModeServesOnlyTestRecords() {
		RestrictedRecordReader test = RestrictedRecordReader.load(TestDataset.FEATURES_DIR,
				DatasetAccessMode.FINAL_EVALUATION, config);
		assertThat(test.split()).isEqualTo("test");
		assertThat(test.contains("201")).isTrue();
		assertThat(test.contains("101")).isFalse();
	}

	@Test
	void recordOutsideTheServedSplitIsNotAvailable() {
		assertThatThrownBy(() -> development.initialFeatures("201")).isInstanceOf(RecordNotAvailableException.class)
			.hasMessageContaining("DEVELOPMENT")
			.hasMessageContaining("validation");
	}

	// --- Only the initial features or exactly one group ---

	@Test
	void initialFeaturesAreExactlyTheConfiguredOnes() {
		Map<String, Double> initial = development.initialFeatures("101");
		assertThat(initial.keySet()).containsExactlyElementsOf(config.initialFeatures());
		initial.forEach((feature, value) -> assertThat(value).isEqualTo(fixtureValue(feature)));
	}

	@Test
	void eachProviderRevealsExactlyItsOwnGroup() {
		for (ProviderGroup group : config.providers()) {
			Map<String, Double> values = development.providerGroup("101", group.id());
			assertThat(values.keySet()).as(group.id()).containsExactlyElementsOf(group.features());
			values.forEach((feature, value) -> assertThat(value).isEqualTo(fixtureValue(feature)));
		}
	}

	@Test
	void initialAndGroupsTogetherCoverEachFeatureOnce() {
		List<String> revealed = new ArrayList<>(development.initialFeatures("101").keySet());
		config.providerIds().forEach(id -> revealed.addAll(development.providerGroup("101", id).keySet()));
		assertThat(revealed).hasSameSizeAs(new HashSet<>(revealed))
			.containsExactlyInAnyOrderElementsOf(config.modelFeatures());
	}

	@Test
	void zerosAndNegativeCodesAreKeptAsValues() {
		Map<String, Double> repayment = development.providerGroup("102", "REPAYMENT_HISTORY");
		assertThat(repayment).containsEntry("PAY_0", -2.0).containsEntry("PAY_3", -1.0).containsEntry("PAY_6", 0.0);
		assertThat(development.initialFeatures("102")).containsEntry("BILL_AMT1", 0.0);
	}

	@Test
	void unknownProviderIsRejected() {
		assertThatThrownBy(() -> development.providerGroup("101", "CREDIT_BUREAU"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void returnedValuesCannotBeModified() {
		assertThatThrownBy(() -> development.initialFeatures("101").put("PAY_0", 1.0))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	// --- Only label-free feature files are accepted ---

	static Stream<Arguments> invalidFiles() {
		return Stream.of(
				Arguments.of("target column added",
						(UnaryOperator<List<String>>) lines -> appendColumn(lines, "default payment next month", "1"),
						"Unexpected columns"),
				Arguments.of("demographic column added",
						(UnaryOperator<List<String>>) lines -> appendColumn(lines, "AGE", "30"), "Unexpected columns"),
				Arguments.of("column missing",
						(UnaryOperator<List<String>>) lines -> lines.stream()
							.map(l -> l.substring(0, l.lastIndexOf(',')))
							.toList(),
						"Unexpected columns"),
				Arguments.of("duplicate record",
						(UnaryOperator<List<String>>) lines -> Stream.concat(lines.stream(), Stream.of(lines.get(1)))
							.toList(),
						"duplicate record ID"),
				Arguments.of("non-numeric value",
						(UnaryOperator<List<String>>) lines -> List.of(lines.get(0), lines.get(1).replace(",1005,", ",x,")),
						"non-numeric"),
				Arguments.of("short row",
						(UnaryOperator<List<String>>) lines -> List.of(lines.get(0), "103,1,2"), "expected"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidFiles")
	void invalidFeatureFileIsRefused(String name, UnaryOperator<List<String>> change, String message)
			throws IOException {
		List<String> lines = Files.readAllLines(TestDataset.FEATURES_DIR.resolve("validation.csv"));
		Files.write(tmp.resolve("validation.csv"), change.apply(lines));

		assertThatThrownBy(() -> RestrictedRecordReader.load(tmp, DatasetAccessMode.DEVELOPMENT, config))
			.isInstanceOf(DatasetException.class)
			.hasMessageContaining(message);
	}

	@Test
	void missingFileExplainsHowToCreateIt() {
		assertThatThrownBy(() -> RestrictedRecordReader.load(tmp, DatasetAccessMode.DEVELOPMENT, config))
			.isInstanceOf(DatasetException.class)
			.hasMessageContaining("ml_service.data.prepare");
	}

	static List<String> appendColumn(List<String> lines, String column, String value) {
		List<String> result = new ArrayList<>();
		result.add(lines.get(0) + "," + column);
		lines.stream().skip(1).forEach(l -> result.add(l + "," + value));
		return result;
	}

	@Test
	void eachSplitFileHasDistinctRecords() {
		RestrictedRecordReader test = RestrictedRecordReader.load(TestDataset.FEATURES_DIR,
				DatasetAccessMode.FINAL_EVALUATION, config);
		Set<String> overlap = new HashSet<>(Set.of("101", "102"));
		overlap.removeIf(id -> !test.contains(id));
		assertThat(overlap).isEmpty();
	}

}
