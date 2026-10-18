package edu.auk.acquisition.feature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Reads {@code config/feature-groups.json}. */
public final class FeatureConfigurationLoader {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private FeatureConfigurationLoader() {
	}

	public static FeatureConfiguration load(Path path) {
		RawConfig raw;
		try {
			raw = MAPPER.readValue(Files.readString(path), RawConfig.class);
		}
		catch (IOException | JacksonException e) {
			throw new FeatureConfigurationException("Cannot read feature configuration " + path.toAbsolutePath(), e);
		}
		if (raw.dataset() == null || raw.providers() == null) {
			throw new FeatureConfigurationException("Malformed feature configuration " + path + ": missing dataset or providers");
		}
		List<ProviderGroup> providers = raw.providers()
			.stream()
			.map(p -> new ProviderGroup(p.id(), p.description(), p.features()))
			.toList();
		return new FeatureConfiguration(raw.configVersion(), raw.dataset().recordIdColumn(),
				raw.dataset().targetColumn(), raw.initialFeatures(), providers, raw.excludedColumns());
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawConfig(String configVersion, RawDataset dataset, List<String> initialFeatures, List<RawProvider> providers,
			List<String> excludedColumns) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawDataset(String recordIdColumn, String targetColumn) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawProvider(String id, String description, List<String> features) {
	}

}
