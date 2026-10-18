package edu.auk.acquisition.dataset;

import java.nio.file.Path;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Which split of label-free feature records the simulator may read ({@code acquisition.dataset}).
 *
 * @param accessMode  {@code DEVELOPMENT} serves validation records; {@code FINAL_EVALUATION} serves test
 *                    records and requires the {@code final-evaluation} profile
 * @param featuresDir directory with the label-free {@code <split>.csv} files written by the data pipeline;
 *                    bound as a string because Spring's {@code Path} conversion breaks relative paths in a jar
 */
@Validated
@ConfigurationProperties("acquisition.dataset")
public record DatasetProperties(@NotNull DatasetAccessMode accessMode, @NotBlank String featuresDir) {

	public Path featuresDirPath() {
		return Path.of(featuresDir);
	}

}
