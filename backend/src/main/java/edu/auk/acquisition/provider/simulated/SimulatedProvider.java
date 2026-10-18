package edu.auk.acquisition.provider.simulated;

import java.util.List;
import java.util.SplittableRandom;

import edu.auk.acquisition.dataset.RestrictedRecordReader;
import edu.auk.acquisition.feature.ProviderGroup;
import edu.auk.acquisition.provider.InformationProvider;
import edu.auk.acquisition.provider.ProviderRequest;
import edu.auk.acquisition.provider.ProviderResult;
import edu.auk.acquisition.scenario.ProviderScenario;
import edu.auk.acquisition.scenario.ProviderSettings;

/**
 * Simulates one provider from the complete experimental records: reveals only its own feature
 * group, reports the scenario latency without waiting (virtual time), and fails
 * deterministically according to the scenario's failure rate and seed (NFR-6).
 */
public final class SimulatedProvider implements InformationProvider {

	static final String FAILURE_REASON = "Simulated provider failure";

	private final ProviderGroup group;
	private final RestrictedRecordReader reader;
	private final ProviderScenario scenario;

	public SimulatedProvider(ProviderGroup group, RestrictedRecordReader reader, ProviderScenario scenario) {
		this.group = group;
		this.reader = reader;
		this.scenario = scenario;
	}

	@Override
	public String id() {
		return group.id();
	}

	@Override
	public List<String> suppliedFeatures() {
		return group.features();
	}

	@Override
	public ProviderResult acquire(ProviderRequest request) {
		ProviderSettings settings = scenario.settings(group.id());
		if (fails(scenario.seed(), request.recordId(), group.id(), settings.failureRate())) {
			return ProviderResult.failure(FAILURE_REASON, settings.latencyMs());
		}
		return ProviderResult.success(reader.providerGroup(request.recordId(), group.id()), settings.latencyMs());
	}

	/**
	 * Whether the call fails: the same scenario seed, record and provider always give the same
	 * answer, so repeated runs reproduce the same failures.
	 */
	static boolean fails(long seed, String recordId, String providerId, double failureRate) {
		if (failureRate <= 0) {
			return false;
		}
		if (failureRate >= 1) {
			return true;
		}
		long key = seed * 0x9E3779B97F4A7C15L + (recordId + "|" + providerId).hashCode();
		return new SplittableRandom(key).nextDouble() < failureRate;
	}

}
