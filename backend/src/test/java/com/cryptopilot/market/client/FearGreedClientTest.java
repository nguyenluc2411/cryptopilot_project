package com.cryptopilot.market.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The Fear &amp; Greed client against a stand-in for alternative.me on the loopback address. */
class FearGreedClientTest {

    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> body = new AtomicReference<>("""
            {"name": "Fear and Greed Index",
             "data": [{"value": "12", "value_classification": "Extreme Fear", "timestamp": "1794873600"}],
             "metadata": {"error": null}}
            """);
    private final AtomicReference<String> query = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/fng/", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readsTodaysReading() {
        try (FearGreedClient client = client()) {
            FearGreedReading reading = client.latest();

            assertThat(query.get()).isEqualTo("limit=1");
            assertThat(reading.value()).isEqualTo(12);
            assertThat(reading.classification()).isEqualTo("Extreme Fear");
            assertThat(reading.asOf()).isEqualTo(Instant.ofEpochSecond(1794873600));
        }
    }

    @Test
    void aRefusalIsTheServiceAnswering_anUnreadableOneIsAFailure() {
        status.set(429);
        try (FearGreedClient client = client()) {
            assertThatThrownBy(client::latest)
                    .isInstanceOfSatisfying(FearGreedException.class, e -> assertThat(e.serviceAnswered())
                            .isTrue());
        }
        status.set(200);
        body.set("{\"data\": [{\"value\": \"abc\"}]}");
        try (FearGreedClient client = client()) {
            assertThatThrownBy(client::latest)
                    .isInstanceOfSatisfying(FearGreedException.class, e -> assertThat(e.serviceAnswered())
                            .isFalse());
        }
        body.set("{\"data\": [{\"value\": \"140\", \"value_classification\": \"Odd\", \"timestamp\": \"1\"}]}");
        try (FearGreedClient client = client()) {
            assertThatThrownBy(client::latest).isInstanceOf(FearGreedException.class);
        }
    }

    @Test
    void aBodyThatStallsMidway_failsTheCallAtTheReadTimeout() {
        server.createContext("/stalled/fng/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("{\"data\": [".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            out.close();
        });
        try (FearGreedClient client = new FearGreedClient(
                new FearGreedProperties(
                        true,
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stalled"),
                        Duration.ofSeconds(2),
                        Duration.ofMillis(500)),
                JsonMapper.builder().build())) {
            long started = System.nanoTime();

            assertThatThrownBy(client::latest)
                    .isInstanceOfSatisfying(FearGreedException.class, e -> assertThat(e.serviceAnswered())
                            .isFalse());
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        }
    }

    private FearGreedClient client() {
        return new FearGreedClient(
                new FearGreedProperties(
                        true,
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2)),
                JsonMapper.builder().build());
    }
}
