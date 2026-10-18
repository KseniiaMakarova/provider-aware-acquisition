package edu.auk.acquisition.feature;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Values of the currently observed features in a legal acquisition state.
 *
 * <p>Can only be created for every initial feature plus whole provider groups, with finite
 * numeric values. The observation mask is exactly the set of keys; observation is never
 * inferred from a value such as zero.
 */
public final class ObservedFeatures {

	private final Map<String, Double> values;
	private final Set<String> acquiredProviders;

	private ObservedFeatures(Map<String, Double> values, Set<String> acquiredProviders) {
		this.values = Collections.unmodifiableMap(values);
		this.acquiredProviders = acquiredProviders;
	}

	public static ObservedFeatures of(Map<String, ? extends Number> values, FeatureConfiguration config) {
		Set<String> acquired = config.acquiredProviders(values.keySet());
		Map<String, Double> ordered = new LinkedHashMap<>();
		for (String feature : config.modelFeatures()) {
			if (!values.containsKey(feature)) {
				continue;
			}
			Number value = values.get(feature);
			if (value == null || !Double.isFinite(value.doubleValue())) {
				throw new InvalidObservationException("Feature " + feature + " must have a finite numeric value");
			}
			ordered.put(feature, value.doubleValue());
		}
		return new ObservedFeatures(ordered, acquired);
	}

	/** Observed values in canonical feature order. */
	public Map<String, Double> values() {
		return values;
	}

	/** The explicit observation mask: names of the observed features, in canonical order. */
	public List<String> mask() {
		return List.copyOf(values.keySet());
	}

	public Set<String> acquiredProviders() {
		return acquiredProviders;
	}

	@Override
	public String toString() {
		return "ObservedFeatures" + mask();
	}

}
