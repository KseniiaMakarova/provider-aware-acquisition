package edu.auk.acquisition.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application settings under the {@code acquisition} prefix.
 *
 * @param featureConfig path of the shared feature/provider grouping file
 * @param mlService     connection settings for the Python inference service
 */
@Validated
@ConfigurationProperties("acquisition")
public record AcquisitionProperties(@NotNull Path featureConfig, @NotNull @Valid MlService mlService) {

	public record MlService(@NotNull URI baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
	}

}
