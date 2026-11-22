package edu.auk.acquisition.provider.simulated;

import java.util.List;
import java.util.SplittableRandom;

import edu.auk.acquisition.dataset.RestrictedRecordReader;
import edu.auk.acquisition.feature.ProviderGroup;
import edu.auk.acquisition.provider.InformationProvider;
import edu.auk.acquisition.provider.ProviderRequest;
import edu.auk.acquisition.provider.ProviderResult;
import edu.auk.acquisition.scenario.ProviderSettings;

/**
 * Simulates one provider from the complete experimental records: reveals only its own feature
 * group, reports a simulated latency without waiting (virtual time), and fails deterministically
 * according to the session scenario's failure rate and seed (NFR-6). The latency is the
 * scenario latency plus reproducible jitter; the executor decides whether that is a timeout.
 */
public final class SimulatedProvider implements InformationProvider {

	static final String FAILURE_REASON = "Simulated provider failure";

	private final ProviderGroup group;

	private final RestrictedRecordReader reader;

	public SimulatedProvider(ProviderGroup group, RestrictedRecordReader reader) {
		this.group = group;
		this.reader = reader;
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
		long seed = request.scenario().seed();
		ProviderSettings settings = request.scenario().settings(group.id());
		long latencyMs = settings.latencyMs() + jitter(seed, request.recordId(), group.id(), settings.latencyJitterMs());
		if (fails(seed, request.recordId(), group.id(), settings.failureRate())) {
			return ProviderResult.failure(FAILURE_REASON, latencyMs);
		}
		return ProviderResult.success(reader.providerGroup(request.recordId(), group.id()), latencyMs);
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

	/**
	 * Extra simulated latency, uniform in {@code 0..maxJitterMs}; reproducible for the same seed,
	 * record and provider, and independent of the failure decision.
	 */
	static long jitter(long seed, String recordId, String providerId, long maxJitterMs) {
		if (maxJitterMs <= 0) {
			return 0;
		}
		long key = seed * 0xC2B2AE3D27D4EB4FL + (recordId + "|" + providerId + "|latency").hashCode();
		return new SplittableRandom(key).nextLong(maxJitterMs + 1);
	}

}
