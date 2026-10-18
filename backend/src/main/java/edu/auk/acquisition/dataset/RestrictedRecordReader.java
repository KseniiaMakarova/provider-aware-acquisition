package edu.auk.acquisition.dataset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ProviderGroup;

/**
 * Privileged, read-only access to the complete feature records of one dataset split.
 *
 * <p>This is the only component that holds unacquired values. It never exposes a whole record:
 * callers can ask for the initial features or for the features of exactly one provider group.
 * Only the initial information loader and the simulated providers may depend on it.
 *
 * <p>The file is the label-free {@code features/<split>.csv} written by the data pipeline. Its
 * header must be exactly the record ID followed by the model features, so a file containing the
 * target, demographic columns or anything else is refused at startup.
 */
public final class RestrictedRecordReader {

	private final FeatureConfiguration config;
	private final DatasetAccessMode accessMode;
	private final Map<String, double[]> records;
	private final Map<String, Integer> columnIndex = new HashMap<>();

	private RestrictedRecordReader(FeatureConfiguration config, DatasetAccessMode accessMode,
			Map<String, double[]> records) {
		this.config = config;
		this.accessMode = accessMode;
		this.records = records;
		List<String> modelFeatures = config.modelFeatures();
		for (int i = 0; i < modelFeatures.size(); i++) {
			columnIndex.put(modelFeatures.get(i), i);
		}
	}

	public static RestrictedRecordReader load(Path featuresDir, DatasetAccessMode accessMode,
			FeatureConfiguration config) {
		Path file = featuresDir.resolve(accessMode.split() + ".csv");
		List<String> lines;
		try {
			lines = Files.readAllLines(file);
		}
		catch (IOException e) {
			throw new DatasetException("Cannot read feature records " + file.toAbsolutePath()
					+ "; run 'python -m ml_service.data.prepare' in ml-service first", e);
		}
		if (lines.isEmpty()) {
			throw new DatasetException("Feature record file is empty: " + file);
		}

		List<String> expectedHeader = new ArrayList<>();
		expectedHeader.add(config.recordIdColumn());
		expectedHeader.addAll(config.modelFeatures());
		List<String> header = List.of(lines.get(0).split(",", -1));
		if (!header.equals(expectedHeader)) {
			throw new DatasetException("Unexpected columns in " + file + ": expected " + expectedHeader + " but found "
					+ header + ". The backend may only read label-free feature files for configuration "
					+ config.version());
		}

		Map<String, double[]> records = new HashMap<>();
		for (int lineNumber = 2; lineNumber <= lines.size(); lineNumber++) {
			String line = lines.get(lineNumber - 1);
			if (line.isBlank()) {
				continue;
			}
			String[] cells = line.split(",", -1);
			if (cells.length != header.size()) {
				throw new DatasetException(file + " line " + lineNumber + ": expected " + header.size() + " values");
			}
			double[] values = new double[cells.length - 1];
			for (int i = 1; i < cells.length; i++) {
				try {
					values[i - 1] = Double.parseDouble(cells[i]);
				}
				catch (NumberFormatException e) {
					throw new DatasetException(file + " line " + lineNumber + ": non-numeric value for " + header.get(i));
				}
				if (!Double.isFinite(values[i - 1])) {
					throw new DatasetException(file + " line " + lineNumber + ": non-finite value for " + header.get(i));
				}
			}
			if (records.put(cells[0], values) != null) {
				throw new DatasetException(file + " line " + lineNumber + ": duplicate record ID " + cells[0]);
			}
		}
		return new RestrictedRecordReader(config, accessMode, Collections.unmodifiableMap(records));
	}

	public DatasetAccessMode accessMode() {
		return accessMode;
	}

	/** Name of the dataset split being served. */
	public String split() {
		return accessMode.split();
	}

	public int size() {
		return records.size();
	}

	public boolean contains(String recordId) {
		return records.containsKey(recordId);
	}

	/** The features known before any provider is queried. */
	public Map<String, Double> initialFeatures(String recordId) {
		return select(recordId, config.initialFeatures());
	}

	/** The features of exactly one provider group. */
	public Map<String, Double> providerGroup(String recordId, String providerId) {
		ProviderGroup group = config.provider(providerId)
			.orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + providerId));
		return select(recordId, group.features());
	}

	private Map<String, Double> select(String recordId, List<String> features) {
		double[] values = records.get(recordId);
		if (values == null) {
			throw new RecordNotAvailableException(recordId, accessMode);
		}
		Map<String, Double> selected = new LinkedHashMap<>();
		for (String feature : features) {
			selected.put(feature, values[columnIndex.get(feature)]);
		}
		return Collections.unmodifiableMap(selected);
	}

}
