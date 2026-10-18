package edu.auk.acquisition.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import edu.auk.acquisition.SharedFeatureConfig;

class ObservedFeaturesTest {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	static Map<String, Number> valuesFor(Set<String> providers, Number value) {
		Map<String, Number> values = new HashMap<>();
		config.observedFeatures(providers).forEach(f -> values.put(f, value));
		return values;
	}

	@Test
	void valuesAreKeptInCanonicalOrderAndTheMaskIsExactlyTheKeys() {
		Map<String, Number> reversed = new LinkedHashMap<>();
		var features = config.observedFeatures(Set.of("PAYMENT_HISTORY"));
		for (int i = features.size() - 1; i >= 0; i--) {
			reversed.put(features.get(i), i * 10);
		}
		ObservedFeatures observed = ObservedFeatures.of(reversed, config);

		assertThat(observed.mask()).isEqualTo(features);
		assertThat(observed.values().keySet()).containsExactlyElementsOf(features);
		assertThat(observed.acquiredProviders()).containsExactly("PAYMENT_HISTORY");
	}

	@Test
	void zeroIsAnObservedValue() {
		ObservedFeatures observed = ObservedFeatures.of(valuesFor(Set.of("REPAYMENT_HISTORY"), 0), config);
		assertThat(observed.mask()).contains("PAY_0", "PAY_6");
		assertThat(observed.values()).containsEntry("PAY_0", 0.0);
	}

	@Test
	void partialGroupIsRejected() {
		Map<String, Number> values = valuesFor(Set.of(), 1);
		values.put("PAY_2", 1);
		assertThatThrownBy(() -> ObservedFeatures.of(values, config)).isInstanceOf(InvalidObservationException.class)
			.hasMessageContaining("Partial group");
	}

	@Test
	void nonFiniteOrMissingValuesAreRejected() {
		for (Number bad : new Number[] { Double.NaN, Double.POSITIVE_INFINITY, null }) {
			Map<String, Number> values = valuesFor(Set.of(), 1);
			values.put("LIMIT_BAL", bad);
			assertThatThrownBy(() -> ObservedFeatures.of(values, config)).isInstanceOf(InvalidObservationException.class)
				.hasMessageContaining("finite numeric value");
		}
	}

	@Test
	void valuesCannotBeModified() {
		ObservedFeatures observed = ObservedFeatures.of(valuesFor(Set.of(), 1), config);
		assertThatThrownBy(() -> observed.values().put("PAY_0", 1.0)).isInstanceOf(UnsupportedOperationException.class);
	}

}
