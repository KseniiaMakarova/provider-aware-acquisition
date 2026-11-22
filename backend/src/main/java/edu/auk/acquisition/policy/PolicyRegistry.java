package edu.auk.acquisition.policy;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Policies available to sessions, by ID, in registration order. */
public class PolicyRegistry {

	private final Map<String, AcquisitionPolicy> policies = new LinkedHashMap<>();

	public PolicyRegistry(Collection<? extends AcquisitionPolicy> policies) {
		for (AcquisitionPolicy policy : policies) {
			if (this.policies.put(policy.id(), policy) != null) {
				throw new IllegalStateException("More than one policy with ID " + policy.id());
			}
		}
	}

	public Optional<AcquisitionPolicy> find(String policyId) {
		return Optional.ofNullable(policies.get(policyId));
	}

	public Set<String> ids() {
		return new TreeSet<>(policies.keySet());
	}

	public List<AcquisitionPolicy> all() {
		return List.copyOf(policies.values());
	}

}
