package edu.auk.acquisition.provider.simulated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.TestDataset;
import edu.auk.acquisition.TestScenarios;
import edu.auk.acquisition.dataset.DatasetAccessMode;
import edu.auk.acquisition.dataset.RestrictedRecordReader;
import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.feature.ProviderGroup;
import edu.auk.acquisition.provider.ProviderRequest;
import edu.auk.acquisition.provider.ProviderResult;
import edu.auk.acquisition.scenario.ProviderSettings;

class SimulatedProviderTest {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	static final RestrictedRecordReader reader = RestrictedRecordReader.load(TestDataset.FEATURES_DIR,
			DatasetAccessMode.DEVELOPMENT, config);

	static final ProviderGroup billing = config.provider("BILLING_HISTORY").orElseThrow();

	static ProviderResult acquireBilling(double failureRate, long latencyMs, String recordId) {
		return acquireBilling(new ProviderSettings(BigDecimal.TWO, latencyMs, true, failureRate), recordId);
	}

	static ProviderResult acquireBilling(ProviderSettings settings, String recordId) {
		return new SimulatedProvider(billing, reader)
			.acquire(new ProviderRequest(recordId, TestScenarios.neutral(Map.of(billing.id(), settings))));
	}

	@Test
	void successRevealsExactlyTheProvidersGroupAndReportsScenarioLatency() {
		ProviderResult result = acquireBilling(0.0, 350, "101");

		assertThat(result.success()).isTrue();
		assertThat(result.values()).containsOnlyKeys(billing.features());
		assertThat(result.values()).isEqualTo(reader.providerGroup("101", billing.id()));
		assertThat(result.simulatedLatencyMs()).isEqualTo(350);
	}

	@Test
	void latencyIsVirtualAndNotWaitedFor() {
		long start = System.nanoTime();
		acquireBilling(0.0, 60_000, "101");
		assertThat(System.nanoTime() - start).isLessThan(1_000_000_000L);
	}

	@Test
	void failedCallRevealsNothingButStillTakesItsLatency() {
		ProviderResult result = acquireBilling(1.0, 350, "101");

		assertThat(result.success()).isFalse();
		assertThat(result.values()).isEmpty();
		assertThat(result.failureReason()).isEqualTo(SimulatedProvider.FAILURE_REASON);
		assertThat(result.simulatedLatencyMs()).isEqualTo(350);
	}

	@Test
	void failuresAreReproducibleForTheSameSeedRecordAndProvider() {
		for (int record = 0; record < 200; record++) {
			String id = String.valueOf(record);
			assertThat(SimulatedProvider.fails(42, id, "BILLING_HISTORY", 0.5))
				.isEqualTo(SimulatedProvider.fails(42, id, "BILLING_HISTORY", 0.5));
		}
	}

	@Test
	void failureRateIsRespectedOverManyRecords() {
		long failures = IntStream.range(0, 10_000)
			.filter(r -> SimulatedProvider.fails(42, String.valueOf(r), "BILLING_HISTORY", 0.2))
			.count();
		assertThat(failures / 10_000.0).isBetween(0.18, 0.22);
	}

	@Test
	void differentSeedsGiveDifferentFailurePatterns() {
		long differing = IntStream.range(0, 1_000)
			.filter(r -> SimulatedProvider.fails(42, String.valueOf(r), "BILLING_HISTORY", 0.5) != SimulatedProvider
				.fails(43, String.valueOf(r), "BILLING_HISTORY", 0.5))
			.count();
		assertThat(differing).isGreaterThan(300);
	}

	@Test
	void zeroAndOneFailureRatesAreExact() {
		assertThat(IntStream.range(0, 1_000).noneMatch(r -> SimulatedProvider.fails(42, "" + r, "X", 0.0))).isTrue();
		assertThat(IntStream.range(0, 1_000).allMatch(r -> SimulatedProvider.fails(42, "" + r, "X", 1.0))).isTrue();
	}

	@Test
	void jitterIsReproducibleAndWithinItsRange() {
		for (int record = 0; record < 2_000; record++) {
			String id = String.valueOf(record);
			long jitter = SimulatedProvider.jitter(42, id, "BILLING_HISTORY", 300);
			assertThat(jitter).isBetween(0L, 300L).isEqualTo(SimulatedProvider.jitter(42, id, "BILLING_HISTORY", 300));
		}
		double mean = IntStream.range(0, 10_000)
			.mapToLong(r -> SimulatedProvider.jitter(42, String.valueOf(r), "BILLING_HISTORY", 300))
			.average()
			.orElseThrow();
		assertThat(mean).isBetween(140.0, 160.0);
	}

	@Test
	void noJitterWithoutConfiguration() {
		assertThat(IntStream.range(0, 100).allMatch(r -> SimulatedProvider.jitter(42, "" + r, "X", 0) == 0)).isTrue();
	}

	@Test
	void simulatedLatencyIsTheScenarioLatencyPlusTheRecordsJitter() {
		ProviderSettings jittery = new ProviderSettings(BigDecimal.ONE, 400, true, 0.0, 2000L, 500);
		for (String record : List.of("101", "102")) {
			assertThat(acquireBilling(jittery, record).simulatedLatencyMs())
				.isEqualTo(400 + SimulatedProvider.jitter(42, record, "BILLING_HISTORY", 500));
		}
	}

	@Test
	void simulatedOutcomesMatchTheProbabilitiesThePolicyUses() {
		// Timeout first, then the failure rate for calls that did not time out, as the executor applies them.
		ProviderSettings settings = new ProviderSettings(BigDecimal.ONE, 600, true, 0.1, 1000L, 800);
		int n = 20_000;
		int timeouts = 0;
		int successes = 0;
		double charged = 0;
		for (int r = 0; r < n; r++) {
			String id = String.valueOf(r);
			long duration = 600 + SimulatedProvider.jitter(42, id, "BILLING_HISTORY", 800);
			if (duration > 1000) {
				timeouts++;
				charged += 1000;
			}
			else {
				charged += duration;
				if (!SimulatedProvider.fails(42, id, "BILLING_HISTORY", 0.1)) {
					successes++;
				}
			}
		}
		assertThat((double) timeouts / n).isCloseTo(settings.timeoutProbability(), within(0.015));
		assertThat((double) successes / n).isCloseTo(settings.successProbability(), within(0.015));
		assertThat(charged / n).isCloseTo(settings.expectedChargedLatencyMs(), within(5.0));
	}

}
