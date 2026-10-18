package edu.auk.acquisition.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.auk.acquisition.provider.ProviderMetadata;
import edu.auk.acquisition.provider.ProviderRegistry;

@RestController
@RequestMapping("/api/v1/providers")
class ProviderController {

	private final ProviderRegistry registry;

	ProviderController(ProviderRegistry registry) {
		this.registry = registry;
	}

	/** Provider definitions and their characteristics in the active scenario; never feature values. */
	@GetMapping
	ProvidersResponse list() {
		return new ProvidersResponse(registry.scenario().version(), registry.metadata());
	}

	record ProvidersResponse(String scenarioVersion, List<ProviderMetadata> providers) {
	}

}
