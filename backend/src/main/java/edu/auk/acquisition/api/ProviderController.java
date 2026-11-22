package edu.auk.acquisition.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import edu.auk.acquisition.provider.ProviderMetadata;
import edu.auk.acquisition.provider.ProviderRegistry;
import edu.auk.acquisition.scenario.ProviderScenario;
import edu.auk.acquisition.scenario.ScenarioCatalog;
import edu.auk.acquisition.session.SessionExceptions.UnsupportedScenarioException;

@RestController
@RequestMapping("/api/v1/providers")
class ProviderController {

	private final ProviderRegistry registry;

	private final ScenarioCatalog scenarios;

	ProviderController(ProviderRegistry registry, ScenarioCatalog scenarios) {
		this.registry = registry;
		this.scenarios = scenarios;
	}

	/**
	 * Provider definitions and their characteristics in a scenario (the default one unless
	 * {@code scenarioId} is given); never feature values.
	 */
	@GetMapping
	ProvidersResponse list(@RequestParam(required = false) String scenarioId) {
		ProviderScenario scenario = scenarioId == null ? scenarios.defaultScenario()
				: scenarios.find(scenarioId)
					.orElseThrow(() -> new UnsupportedScenarioException(scenarioId,
							scenarios.all().stream().map(ProviderScenario::version).toList()));
		return new ProvidersResponse(scenario.version(), registry.metadata(scenario));
	}

	record ProvidersResponse(String scenarioVersion, List<ProviderMetadata> providers) {
	}

}
