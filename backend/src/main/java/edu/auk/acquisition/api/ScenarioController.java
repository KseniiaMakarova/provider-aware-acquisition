package edu.auk.acquisition.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.auk.acquisition.provider.ProviderMetadata;
import edu.auk.acquisition.provider.ProviderRegistry;
import edu.auk.acquisition.scenario.ProviderScenario;
import edu.auk.acquisition.scenario.ScenarioCatalog;

@RestController
@RequestMapping("/api/v1/scenarios")
class ScenarioController {

	private final ScenarioCatalog scenarios;

	private final ProviderRegistry registry;

	ScenarioController(ScenarioCatalog scenarios, ProviderRegistry registry) {
		this.scenarios = scenarios;
		this.registry = registry;
	}

	/** The predefined scenarios a session can choose, with every provider's characteristics. */
	@GetMapping
	ScenariosResponse list() {
		return new ScenariosResponse(scenarios.defaultScenario().version(),
				scenarios.all().stream().map(s -> ScenarioView.of(s, registry.metadata(s))).toList());
	}

	record ScenariosResponse(String defaultScenario, List<ScenarioView> scenarios) {
	}

	record ScenarioView(String id, String description, long seed, List<ProviderMetadata> providers) {

		static ScenarioView of(ProviderScenario scenario, List<ProviderMetadata> providers) {
			return new ScenarioView(scenario.version(), scenario.description(), scenario.seed(), providers);
		}

	}

}
