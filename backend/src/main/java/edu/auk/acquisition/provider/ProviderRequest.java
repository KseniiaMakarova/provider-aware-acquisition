package edu.auk.acquisition.provider;

import edu.auk.acquisition.scenario.ProviderScenario;

/**
 * What a provider needs for one call: the key of the record being acquired and the session's
 * scenario, which simulated providers use to simulate their latency and failures.
 */
public record ProviderRequest(String recordId, ProviderScenario scenario) {
}
