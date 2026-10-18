package edu.auk.acquisition.feature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Shared feature and provider grouping, loaded from {@code config/feature-groups.json}.
 *
 * <p>The Python ML service reads the same file and applies the same rules, so both sides agree
 * on which features are initially known, which features each provider reveals, and which
 * columns must never be model inputs.
 */
public final class FeatureConfiguration {

	private final String version;
	private final String recordIdColumn;
	private final String targetColumn;
	private final List<String> initialFeatures;
	private final List<ProviderGroup> providers;
	private final List<String> excludedColumns;

	public FeatureConfiguration(String version, String recordIdColumn, String targetColumn,
			List<String> initialFeatures, List<ProviderGroup> providers, List<String> excludedColumns) {
		this.version = version;
		this.recordIdColumn = recordIdColumn;
		this.targetColumn = targetColumn;
		this.initialFeatures = initialFeatures == null ? List.of() : List.copyOf(initialFeatures);
		this.providers = providers == null ? List.of() : List.copyOf(providers);
		this.excludedColumns = excludedColumns == null ? List.of() : List.copyOf(excludedColumns);
		validate();
	}

	public String version() {
		return version;
	}

	public String recordIdColumn() {
		return recordIdColumn;
	}

	public String targetColumn() {
		return targetColumn;
	}

	public List<String> initialFeatures() {
		return initialFeatures;
	}

	public List<ProviderGroup> providers() {
		return providers;
	}

	public List<String> excludedColumns() {
		return excludedColumns;
	}

	public List<String> providerIds() {
		return providers.stream().map(ProviderGroup::id).toList();
	}

	public Optional<ProviderGroup> provider(String providerId) {
		return providers.stream().filter(p -> p.id().equals(providerId)).findFirst();
	}

	/** All model inputs in canonical order: initial features, then each group in file order. */
	public List<String> modelFeatures() {
		return Stream.concat(initialFeatures.stream(), providers.stream().flatMap(p -> p.features().stream()))
			.toList();
	}

	/** Every legal set of acquired providers, from none to all. */
	public List<Set<String>> observationStates() {
		List<Set<String>> states = new ArrayList<>();
		int n = providers.size();
		for (int mask = 0; mask < (1 << n); mask++) {
			Set<String> state = new LinkedHashSet<>();
			for (int i = 0; i < n; i++) {
				if ((mask & (1 << i)) != 0) {
					state.add(providers.get(i).id());
				}
			}
			states.add(Set.copyOf(state));
		}
		return states;
	}

	/** Features visible once the given providers have been acquired, in canonical order. */
	public List<String> observedFeatures(Collection<String> acquiredProviders) {
		Set<String> unknown = new TreeSet<>(acquiredProviders);
		unknown.removeAll(providerIds());
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException("Unknown provider(s): " + unknown);
		}
		Set<String> visible = new HashSet<>(initialFeatures);
		providers.stream()
			.filter(p -> acquiredProviders.contains(p.id()))
			.forEach(p -> visible.addAll(p.features()));
		return modelFeatures().stream().filter(visible::contains).toList();
	}

	/**
	 * Maps an observed feature set back to its provider state.
	 *
	 * @throws InvalidObservationException unless the set is every initial feature plus whole groups
	 */
	public Set<String> acquiredProviders(Collection<String> observedFeatures) {
		Set<String> observed = new HashSet<>(observedFeatures);
		Set<String> notAllowed = new TreeSet<>(observed);
		notAllowed.removeAll(modelFeatures());
		if (!notAllowed.isEmpty()) {
			throw new InvalidObservationException("Features not allowed as model inputs: " + notAllowed);
		}
		Set<String> missingInitial = new TreeSet<>(initialFeatures);
		missingInitial.removeAll(observed);
		if (!missingInitial.isEmpty()) {
			throw new InvalidObservationException("Initial features missing: " + missingInitial);
		}
		Set<String> acquired = new LinkedHashSet<>();
		for (ProviderGroup p : providers) {
			long present = p.features().stream().filter(observed::contains).count();
			if (present > 0 && present < p.features().size()) {
				Set<String> missing = new TreeSet<>(p.features());
				missing.removeAll(observed);
				throw new InvalidObservationException("Partial group for " + p.id() + ": missing " + missing);
			}
			if (present > 0) {
				acquired.add(p.id());
			}
		}
		return Set.copyOf(acquired);
	}

	private void validate() {
		if (version == null || version.isBlank()) {
			throw new FeatureConfigurationException("configVersion must not be empty");
		}
		if (recordIdColumn == null || targetColumn == null) {
			throw new FeatureConfigurationException("dataset.recordIdColumn and dataset.targetColumn are required");
		}
		if (initialFeatures.isEmpty()) {
			throw new FeatureConfigurationException("initialFeatures must not be empty");
		}
		if (providers.isEmpty()) {
			throw new FeatureConfigurationException("At least one provider is required");
		}
		Set<String> ids = new HashSet<>();
		for (ProviderGroup p : providers) {
			if (p.id() == null || p.id().isBlank() || p.features() == null || p.features().isEmpty()) {
				throw new FeatureConfigurationException("Provider needs an id and at least one feature: " + p);
			}
			if (!ids.add(p.id())) {
				throw new FeatureConfigurationException("Duplicate provider ids: " + providerIds());
			}
		}
		Set<String> seen = new HashSet<>();
		Set<String> duplicates = new TreeSet<>();
		for (String feature : modelFeatures()) {
			if (!seen.add(feature)) {
				duplicates.add(feature);
			}
		}
		if (!duplicates.isEmpty()) {
			throw new FeatureConfigurationException("Features assigned more than once: " + duplicates);
		}
		Set<String> leaked = new TreeSet<>(excludedColumns);
		leaked.add(recordIdColumn);
		leaked.add(targetColumn);
		leaked.retainAll(seen);
		if (!leaked.isEmpty()) {
			throw new FeatureConfigurationException("ID, target or excluded columns used as features: " + leaked);
		}
	}

}
