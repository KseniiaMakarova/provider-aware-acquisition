package edu.auk.acquisition.provider;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.auk.acquisition.scenario.ProviderSettings;

/**
 * Validates and performs a provider call and records its outcome.
 *
 * <p>Charging rules for the Alpha (experimental assumptions): a successful call is charged the
 * configured cost and its simulated latency; a failed call is charged no cost but its simulated
 * latency still accumulates; unavailable providers are never called.
 */
public class ProviderExecutor {

	private static final Logger log = LoggerFactory.getLogger(ProviderExecutor.class);

	private final ProviderRegistry registry;

	public ProviderExecutor(ProviderRegistry registry) {
		this.registry = registry;
	}

	/**
	 * @param alreadyAttempted providers attempted earlier in the session, successful or not
	 * @throws ProviderContractException if the call is not allowed or the provider breaks its contract
	 */
	public ProviderAttempt execute(String providerId, String recordId, Collection<String> alreadyAttempted) {
		ProviderMetadata metadata = registry.metadata(providerId)
			.orElseThrow(() -> new ProviderContractException("Unknown provider " + providerId));
		if (!metadata.available()) {
			throw new ProviderContractException("Provider " + providerId + " is unavailable in scenario "
					+ registry.scenario().version() + " and must not be selected");
		}
		if (alreadyAttempted.contains(providerId)) {
			throw new ProviderContractException("Provider " + providerId + " was already attempted in this session");
		}

		ProviderSettings settings = registry.scenario().settings(providerId);
		long start = System.nanoTime();
		ProviderResult result = registry.implementation(providerId).acquire(new ProviderRequest(recordId));
		Duration executionTime = Duration.ofNanos(System.nanoTime() - start);

		ProviderAttempt attempt;
		if (result.success()) {
			if (!new HashSet<>(metadata.suppliedFeatures()).equals(result.values().keySet())) {
				throw new ProviderContractException("Provider " + providerId + " returned " + result.values().keySet()
						+ " instead of its declared features " + metadata.suppliedFeatures());
			}
			attempt = new ProviderAttempt(providerId, ProviderAttempt.Status.SUCCESS, result.values(), settings.cost(),
					settings.cost(), result.simulatedLatencyMs(), executionTime, null);
		}
		else {
			attempt = new ProviderAttempt(providerId, ProviderAttempt.Status.FAILED, Map.of(),
					settings.cost(), BigDecimal.ZERO, result.simulatedLatencyMs(), executionTime, result.failureReason());
		}
		log.debug("Provider {} for record {}: {} (charged {}, simulated {} ms)", providerId, recordId,
				attempt.status(), attempt.chargedCost(), attempt.simulatedLatencyMs());
		return attempt;
	}

}
