package edu.auk.acquisition.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.auk.acquisition.policy.AcquisitionPolicy;
import edu.auk.acquisition.policy.PolicyRegistry;

@RestController
@RequestMapping("/api/v1/policies")
class PolicyController {

	private final PolicyRegistry registry;

	PolicyController(PolicyRegistry registry) {
		this.registry = registry;
	}

	/** Acquisition policies a session can use, with their versions and decision parameters. */
	@GetMapping
	PoliciesResponse list() {
		return new PoliciesResponse(registry.all().stream().map(PolicyView::of).toList());
	}

	record PoliciesResponse(List<PolicyView> policies) {
	}

	record PolicyView(String id, String version, String description, boolean usesValueEstimates,
			Map<String, Object> parameters) {

		static PolicyView of(AcquisitionPolicy policy) {
			return new PolicyView(policy.id(), policy.version(), policy.description(),
					policy.valueSampleSize().isPresent(), policy.parameters());
		}

	}

}
