package edu.auk.acquisition.policy;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Policies available to sessions, by ID. */
public class PolicyRegistry {

	private final Map<String, AcquisitionPolicy> policies;

	public PolicyRegistry(Collection<? extends AcquisitionPolicy> policies) {
		this.policies = policies.stream().collect(Collectors.toUnmodifiableMap(AcquisitionPolicy::id, Function.identity()));
	}

	public Optional<AcquisitionPolicy> find(String policyId) {
		return Optional.ofNullable(policies.get(policyId));
	}

	public Set<String> ids() {
		return new TreeSet<>(policies.keySet());
	}

}
