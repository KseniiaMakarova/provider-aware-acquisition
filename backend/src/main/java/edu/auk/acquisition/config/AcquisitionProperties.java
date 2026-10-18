package edu.auk.acquisition.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application settings under the {@code acquisition} prefix. Dataset access settings live in
 * {@code edu.auk.acquisition.dataset.DatasetProperties}.
 *
 * <p>File locations are bound as plain strings and converted with {@link Path#of}: Spring's own
 * {@code Path} conversion treats them as resource locations, which breaks relative paths such
 * as {@code ../config} when the application runs from a packaged jar.
 *
 * @param featureConfig  path of the shared feature/provider grouping file
 * @param scenarioConfig path of the provider scenario (cost, latency, availability, failures)
 * @param mlService      connection settings for the Python inference service
 */
@Validated
@ConfigurationProperties("acquisition")
public record AcquisitionProperties(@NotBlank String featureConfig, @NotBlank String scenarioConfig,
		@NotNull @Valid MlService mlService) {

	public Path featureConfigPath() {
		return Path.of(featureConfig);
	}

	public Path scenarioConfigPath() {
		return Path.of(scenarioConfig);
	}

	public record MlService(@NotNull URI baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
	}

}
