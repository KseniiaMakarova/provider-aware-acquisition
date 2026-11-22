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
 * @param featureConfig        path of the shared feature/provider grouping file
 * @param scenariosDir         folder with the predefined provider scenarios (one JSON file each)
 * @param defaultScenario      version of the scenario used when a session does not choose one
 * @param adaptivePolicyConfig path of the versioned adaptive policy settings
 * @param mlService            connection settings for the Python inference service
 * @param advanceLease         how long one /advance may hold its claim on a session before another
 *                             request may take over; must exceed the longest possible advance (an
 *                             adaptive advance makes two ML calls, each up to the connect plus
 *                             read timeout)
 */
@Validated
@ConfigurationProperties("acquisition")
public record AcquisitionProperties(@NotBlank String featureConfig, @NotBlank String scenariosDir,
		@NotBlank String defaultScenario, @NotBlank String adaptivePolicyConfig, @NotNull @Valid MlService mlService,
		@NotNull Duration advanceLease) {

	public Path featureConfigPath() {
		return Path.of(featureConfig);
	}

	public Path scenariosDirPath() {
		return Path.of(scenariosDir);
	}

	public Path adaptivePolicyConfigPath() {
		return Path.of(adaptivePolicyConfig);
	}

	public record MlService(@NotNull URI baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
	}

}
