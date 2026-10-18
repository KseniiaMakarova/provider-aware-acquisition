package edu.auk.acquisition.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import edu.auk.acquisition.SharedFeatureConfig;
import edu.auk.acquisition.config.AcquisitionProperties;
import edu.auk.acquisition.feature.FeatureConfiguration;

/** Exercises the real HTTP stack (JDK client, timeouts, JSON) against a minimal local server. */
class HttpPredictionClientNetworkTest {

	static final FeatureConfiguration config = SharedFeatureConfig.CONFIG;

	HttpServer server;

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	int startServer(long delayMillis, String responseBody, AtomicReference<String> receivedBody) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext(HttpPredictionClient.PREDICT_PATH, exchange -> respond(exchange, delayMillis, responseBody,
				receivedBody));
		server.start();
		return server.getAddress().getPort();
	}

	static void respond(HttpExchange exchange, long delayMillis, String body, AtomicReference<String> received)
			throws IOException {
		received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		try {
			Thread.sleep(delayMillis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	static HttpPredictionClient clientFor(int port, Duration readTimeout) {
		var settings = new AcquisitionProperties.MlService(URI.create("http://127.0.0.1:" + port),
				Duration.ofMillis(500), readTimeout);
		return new HttpPredictionClient(PredictionClientConfig.mlServiceRestClient(settings), config);
	}

	@Test
	void predictionOverRealHttp() throws IOException {
		AtomicReference<String> received = new AtomicReference<>();
		int port = startServer(0, """
				{"probability": 0.42, "modelVersion": "mask-hgb-test", "featureConfigVersion": "%s"}
				""".formatted(config.version()), received);

		Prediction prediction = clientFor(port, Duration.ofSeconds(2)).predict(HttpPredictionClientTest.initialState());

		assertThat(prediction.probability()).isEqualTo(0.42);
		assertThat(received.get()).contains("\"observedMask\"").doesNotContain("PAY_0");
	}

	@Test
	void slowServiceTimesOut() throws IOException {
		int port = startServer(2_000, "{}", new AtomicReference<>());
		HttpPredictionClient client = clientFor(port, Duration.ofMillis(300));

		long start = System.nanoTime();
		assertThatThrownBy(() -> client.predict(HttpPredictionClientTest.initialState()))
			.isInstanceOf(PredictionUnavailableException.class);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_500));
	}

	@Test
	void unreachableServiceIsUnavailable() throws IOException {
		int port = startServer(0, "{}", new AtomicReference<>());
		server.stop(0);
		server = null;

		assertThatThrownBy(() -> clientFor(port, Duration.ofSeconds(1)).predict(HttpPredictionClientTest.initialState()))
			.isInstanceOf(PredictionUnavailableException.class)
			.hasMessageContaining("request failed");
	}

}
