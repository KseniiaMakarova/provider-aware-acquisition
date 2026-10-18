package edu.auk.acquisition.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import edu.auk.acquisition.dataset.DatasetAccessMode;

/**
 * Application settings under the {@code acquisition} prefix.
 *
 * <p>File locations are bound as plain strings and converted with {@link Path#of}: Spring's own
 * {@code Path} conversion treats them as resource locations, which breaks relative paths such
 * as {@code ../config} when the application runs from a packaged jar.
 *
 * @param featureConfig path of the shared feature/provider grouping file
 * @param dataset       which split of label-free feature records the simulator may read
 * @param mlService     connection settings for the Python inference service
 */
@Validated
@ConfigurationProperties("acquisition")
public record AcquisitionProperties(@NotBlank String featureConfig, @NotNull @Valid Dataset dataset,
		@NotNull @Valid MlService mlService) {

	public Path featureConfigPath() {
		return Path.of(featureConfig);
	}

	/**
	 * @param accessMode  {@code DEVELOPMENT} serves validation records; {@code FINAL_EVALUATION} serves
	 *                    test records and requires the {@code final-evaluation} profile
	 * @param featuresDir directory with the label-free {@code <split>.csv} files written by the data pipeline
	 */
	public record Dataset(@NotNull DatasetAccessMode accessMode, @NotBlank String featuresDir) {

		public Path featuresDirPath() {
			return Path.of(featuresDir);
		}

	}

	public record MlService(@NotNull URI baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
	}

}
