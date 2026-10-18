package edu.auk.acquisition.scenario;

import java.math.BigDecimal;

/**
 * Operational characteristics of one provider in a scenario, configured independently of the
 * features it supplies (FR-2).
 *
 * @param cost        charged for a successful call, in experimental units
 * @param latencyMs   simulated duration of a call; recorded, not waited for
 * @param available   unavailable providers are not feasible and are never called (ER-9)
 * @param failureRate probability that a call fails, decided deterministically from the scenario seed
 */
public record ProviderSettings(BigDecimal cost, long latencyMs, boolean available, double failureRate) {

}
