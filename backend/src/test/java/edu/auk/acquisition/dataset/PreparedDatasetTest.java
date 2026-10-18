package edu.auk.acquisition.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.feature.FeatureConfiguration;

/**
 * Checks the Java reader against the real files written by the Python data pipeline. Runs only
 * after {@code python -m ml_service.data.prepare}.
 */
@EnabledIf("dataPrepared")
class PreparedDatasetTest {

	static final Path DATA = Path.of("..", "data");

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	static boolean dataPrepared() {
		return Files.exists(DATA.resolve("features").resolve("validation.csv"));
	}

	static Set<String> manifestIds(String split) throws IOException {
		return Files.readAllLines(DATA.resolve("split-manifest.csv"))
			.stream()
			.skip(1)
			.map(line -> line.split(","))
			.filter(cells -> cells[1].equals(split))
			.map(cells -> cells[0])
			.collect(Collectors.toSet());
	}

	@Test
	void developmentModeServesExactlyTheValidationSplit() throws IOException {
		RestrictedRecordReader reader = RestrictedRecordReader.load(DATA.resolve("features"),
				DatasetAccessMode.DEVELOPMENT, config);
		Set<String> validation = manifestIds("validation");

		assertThat(reader.size()).isEqualTo(4_500).isEqualTo(validation.size());
		assertThat(validation).allMatch(reader::contains);
		assertThat(manifestIds("train")).noneMatch(reader::contains);
		assertThat(manifestIds("test")).noneMatch(reader::contains);
	}

	@Test
	void valuesMatchTheFileWrittenByThePipeline() throws IOException {
		List<String> lines = Files.readAllLines(DATA.resolve("features").resolve("validation.csv"));
		String[] header = lines.get(0).split(",");
		String[] first = lines.get(1).split(",");
		RestrictedRecordReader reader = RestrictedRecordReader.load(DATA.resolve("features"),
				DatasetAccessMode.DEVELOPMENT, config);

		Map<String, Double> initial = reader.initialFeatures(first[0]);
		for (int i = 1; i < header.length; i++) {
			if (initial.containsKey(header[i])) {
				assertThat(initial.get(header[i])).isEqualTo(Double.parseDouble(first[i]));
			}
		}
		assertThat(initial).hasSize(config.initialFeatures().size());
	}

}
