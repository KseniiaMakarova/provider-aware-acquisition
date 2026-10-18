package edu.auk.acquisition.provider.simulated;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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

	static SimulatedProvider billingWith(double failureRate, long latencyMs) {
		return new SimulatedProvider(billing, reader, TestScenarios.neutral(
				Map.of(billing.id(), new ProviderSettings(BigDecimal.TWO, latencyMs, true, failureRate))));
	}

	@Test
	void successRevealsExactlyTheProvidersGroupAndReportsScenarioLatency() {
		ProviderResult result = billingWith(0.0, 350).acquire(new ProviderRequest("101"));

		assertThat(result.success()).isTrue();
		assertThat(result.values()).containsOnlyKeys(billing.features());
		assertThat(result.values()).isEqualTo(reader.providerGroup("101", billing.id()));
		assertThat(result.simulatedLatencyMs()).isEqualTo(350);
	}

	@Test
	void latencyIsVirtualAndNotWaitedFor() {
		long start = System.nanoTime();
		billingWith(0.0, 60_000).acquire(new ProviderRequest("101"));
		assertThat(System.nanoTime() - start).isLessThan(1_000_000_000L);
	}

	@Test
	void failedCallRevealsNothingButStillTakesItsLatency() {
		ProviderResult result = billingWith(1.0, 350).acquire(new ProviderRequest("101"));

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

}
