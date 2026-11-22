package edu.auk.acquisition.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ProviderSettingsTest {

	static ProviderSettings settings(long latencyMs, Long timeoutMs, long jitterMs, double failureRate) {
		return new ProviderSettings(BigDecimal.ONE, latencyMs, true, failureRate, timeoutMs, jitterMs);
	}

	@Test
	void withoutTimeoutAndJitterTheCallAlwaysTakesItsLatency() {
		ProviderSettings s = new ProviderSettings(BigDecimal.ONE, 200, true, 0.25);
		assertThat(s.timeoutMs()).isNull();
		assertThat(s.latencyJitterMs()).isZero();
		assertThat(s.timeoutProbability()).isZero();
		assertThat(s.expectedChargedLatencyMs()).isEqualTo(200);
		assertThat(s.successProbability()).isEqualTo(0.75);
	}

	@Test
	void jitterWithoutTimeoutAddsHalfTheJitterOnAverage() {
		assertThat(settings(600, null, 800, 0).expectedChargedLatencyMs()).isEqualTo(1000);
	}

	@Test
	void timeoutProbabilityAndChargedTimeFollowTheUniformJitter() {
		// Durations 600..1400 ms, timeout 1000 ms: 400 of the 801 equally likely durations time out.
		ProviderSettings s = settings(600, 1000L, 800, 0.1);
		assertThat(s.timeoutProbability()).isCloseTo(400.0 / 801, within(1e-12));
		double charged = 0;
		for (int j = 0; j <= 800; j++) {
			charged += Math.min(600 + j, 1000);
		}
		assertThat(s.expectedChargedLatencyMs()).isCloseTo(charged / 801, within(1e-9));
		assertThat(s.successProbability()).isCloseTo(0.9 * (1 - 400.0 / 801), within(1e-12));
	}

	@Test
	void callsAlwaysOrNeverTimingOut() {
		assertThat(settings(800, 500L, 0, 0).timeoutProbability()).isEqualTo(1.0);
		assertThat(settings(800, 500L, 0, 0).expectedChargedLatencyMs()).isEqualTo(500);
		assertThat(settings(800, 500L, 0, 0).successProbability()).isZero();
		assertThat(settings(300, 700L, 400, 0).timeoutProbability()).isZero();
		assertThat(settings(300, 700L, 400, 0).expectedChargedLatencyMs()).isEqualTo(500);
	}

}
