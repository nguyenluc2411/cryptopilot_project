package com.cryptopilot.market.client.fallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OKX client against a stand-in on the loopback address (D-159): the tickers of each market, the candles oldest
 * first, and every failure, an HTTP error or an error code in the body, an {@link OkxException}.
 */
class OkxClientTest {

    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v5/market", exchange -> {
            query.set(exchange.getRequestURI().getPath() + "?"
                    + exchange.getRequestURI().getQuery());
            send(exchange, status.get(), body.get());
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void D159_readsTheTickersOfEachMarket_leavingOutOnesWithoutAPrice() {
        body.set("""
                {"code": "0", "msg": "", "data": [
                  {"instId": "BTC-USDT", "last": "65000.1", "open24h": "64000", "high24h": "66000", "low24h": "63000",
                   "vol24h": "1234.5", "ts": "1794893964000"},
                  {"instId": "ETH-USDT", "last": ""},
                  {"last": "1"}
                ]}""");
        try (OkxClient client = client()) {
            List<OkxTicker> spot = client.spotTickers();

            assertThat(query.get()).isEqualTo("/api/v5/market/tickers?instType=SPOT");
            assertThat(spot).singleElement().satisfies(ticker -> {
                assertThat(ticker.instrumentId()).isEqualTo("BTC-USDT");
                assertThat(ticker.lastPrice()).isEqualByComparingTo("65000.1");
                assertThat(ticker.open24h()).isEqualByComparingTo("64000");
                assertThat(ticker.volume24h()).isEqualByComparingTo("1234.5");
                assertThat(ticker.at()).isEqualTo(Instant.ofEpochMilli(1794893964000L));
            });

            client.swapTickers();
            assertThat(query.get()).isEqualTo("/api/v5/market/tickers?instType=SWAP");
        }
    }

    @Test
    void D159_readsTheMinuteCandles_oldestFirst() {
        body.set("""
                {"code": "0", "data": [
                  ["1794894060000", "101", "102", "100", "101.5", "3", "300", "300", "0"],
                  ["1794894000000", "100", "101", "99", "101", "2", "200", "200", "1"]
                ]}""");
        try (OkxClient client = client()) {
            List<OkxCandle> candles = client.minuteCandles("BTC-USDT-SWAP", 2);

            assertThat(query.get()).isEqualTo("/api/v5/market/candles?bar=1m&limit=2&instId=BTC-USDT-SWAP");
            assertThat(candles)
                    .extracting(OkxCandle::openTime)
                    .containsExactly(Instant.ofEpochMilli(1794894000000L), Instant.ofEpochMilli(1794894060000L));
            assertThat(candles.getFirst().closed()).isTrue();
            assertThat(candles.getLast().closed()).isFalse();
            assertThat(candles.getLast().close()).isEqualByComparingTo(new BigDecimal("101.5"));
        }
        try (OkxClient client = client()) {
            assertThatThrownBy(() -> client.minuteCandles("BTC-USDT", 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.minuteCandles("BTC-USDT", 301))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void D159_everyFailure_isAnOkxException() {
        try (OkxClient client = client()) {
            status.set(503);
            body.set("busy");
            assertThatThrownBy(client::spotTickers)
                    .isInstanceOfSatisfying(
                            OkxException.class, e -> assertThat(e.status()).isEqualTo(503));

            status.set(200);
            body.set("{\"code\": \"50011\", \"msg\": \"Too Many Requests\", \"data\": []}");
            assertThatThrownBy(client::spotTickers)
                    .isInstanceOf(OkxException.class)
                    .hasMessageContaining("50011");

            body.set("{\"code\": \"0\"}");
            assertThatThrownBy(client::swapTickers)
                    .isInstanceOf(OkxException.class)
                    .hasMessageContaining("data");

            body.set("not json");
            assertThatThrownBy(client::spotTickers)
                    .isInstanceOfSatisfying(
                            OkxException.class, e -> assertThat(e.status()).isZero());

            body.set("{\"code\": \"0\", \"data\": [{\"bad\": true}]}");
            assertThatThrownBy(() -> client.minuteCandles("BTC-USDT", 1)).isInstanceOf(OkxException.class);
        }
        server.stop(0);
        try (OkxClient client = client()) {
            assertThatThrownBy(client::spotTickers)
                    .isInstanceOfSatisfying(
                            OkxException.class, e -> assertThat(e.status()).isZero());
        }
    }

    @Test
    void D159_theOverrides_areKeyedByMarketAndSymbol_inUpperCase() {
        OkxProperties properties = new OkxProperties(
                true,
                URI.create("https://www.okx.com"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(3),
                new BigDecimal("0.05"),
                Map.of(" futures.1000pepeusdt ", " pepe-usdt-swap", "SPOT.X", " "));

        assertThat(properties.overrides()).containsExactly(Map.entry("FUTURES.1000PEPEUSDT", "PEPE-USDT-SWAP"));
        assertThatThrownBy(() -> new OkxProperties(
                        true,
                        URI.create("https://x"),
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        BigDecimal.ZERO,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private OkxClient client() {
        return new OkxClient(
                new OkxProperties(
                        true,
                        URI.create("http://" + server.getAddress().getHostString() + ":"
                                + server.getAddress().getPort()),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(3),
                        new BigDecimal("0.05"),
                        Map.of()),
                JsonMapper.builder().build());
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
