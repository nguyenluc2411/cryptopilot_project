package com.cryptopilot.market.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The client against a stand-in for CoinPaprika on the loopback address: the coins it keeps from the list, the logo of
 * one coin, and every failure turned into a {@link CoinPaprikaException} that says whether CoinPaprika answered.
 */
class CoinPaprikaClientTest {

    private static final String LIST = """
            [
              {"id": "btc-bitcoin", "name": "Bitcoin", "symbol": "BTC", "rank": 1, "is_new": false,
               "is_active": true, "type": "coin"},
              {"id": "usdt-tether", "name": "Tether", "symbol": "usdt", "rank": 3, "is_active": true},
              {"id": "dead-coin", "name": "Dead", "symbol": "DEAD", "rank": 0, "is_active": false},
              {"id": "nameless", "name": "", "symbol": "NN", "rank": 9, "is_active": true},
              {"name": "No id", "symbol": "NOID", "rank": 9, "is_active": true}
            ]
            """;

    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> list = new AtomicReference<>(LIST);
    private final AtomicReference<String> path = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/coins", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            String body = path.get().equals("/v1/coins")
                    ? list.get()
                    : path.get().equals("/v1/coins/btc-bitcoin")
                            ? "{\"id\":\"btc-bitcoin\",\"logo\":\"https://static.coinpaprika.com/coin/btc-bitcoin/logo.png\"}"
                            : "{\"id\":\"x\",\"logo\":\"http://insecure.example/x.png\"}";
            send(exchange, status.get(), body);
        });
        server.createContext("/v1/tickers", exchange -> send(exchange, status.get(), """
                [
                  {"id": "btc-bitcoin", "symbol": "BTC", "rank": 1, "circulating_supply": 19900000,
                   "quotes": {"USD": {"price": 65000, "market_cap": 1300000000000}}},
                  {"id": "eth-ethereum", "symbol": "eth", "rank": 2,
                   "quotes": {"USD": {"price": 2000, "market_cap": 244000000000}}},
                  {"id": "x-total", "symbol": "XT", "rank": 9, "total_supply": 500, "quotes": {"USD": {}}},
                  {"id": "unranked", "symbol": "UR", "rank": 0}
                ]
                """));
        server.createContext("/v1/global", exchange -> send(exchange, status.get(), """
                {"market_cap_usd": 2309534819143, "volume_24h_usd": 310628283934,
                 "bitcoin_dominance_percentage": 56.58, "market_cap_change_24h": 0.76, "last_updated": 1794893964}
                """));
        server.start();
    }

    @Test
    void readsTheRankedTickersWithTheirSupply() {
        try (CoinPaprikaClient client = client()) {
            List<CoinPaprikaTicker> tickers = client.tickers();

            assertThat(tickers).extracting(CoinPaprikaTicker::symbol).containsExactly("BTC", "ETH", "XT");
            // Its own supply; else market cap over price; never the total supply, which counts coins not circulating.
            assertThat(tickers.get(0).circulatingSupply()).isEqualByComparingTo("19900000");
            assertThat(tickers.get(1).circulatingSupply()).isEqualByComparingTo("122000000");
            assertThat(tickers.get(2).circulatingSupply()).isNull();
        }
    }

    @Test
    void readsTheWholeMarket() {
        try (CoinPaprikaClient client = client()) {
            CoinPaprikaGlobal global = client.global();

            assertThat(global.marketCapUsd()).isEqualByComparingTo("2309534819143");
            assertThat(global.marketCapChange24hPercent()).isEqualByComparingTo("0.76");
            assertThat(global.bitcoinDominancePercent()).isEqualByComparingTo("56.58");
            assertThat(global.asOf()).isEqualTo(java.time.Instant.ofEpochSecond(1794893964));
        }
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readsTheListAndKeepsOnlyUsableCoins() {
        try (CoinPaprikaClient client = client()) {
            List<CoinPaprikaCoin> coins = client.coins();

            assertThat(coins)
                    .containsExactly(
                            new CoinPaprikaCoin("btc-bitcoin", "BTC", "Bitcoin", 1, true),
                            new CoinPaprikaCoin("usdt-tether", "USDT", "Tether", 3, true),
                            new CoinPaprikaCoin("dead-coin", "DEAD", "Dead", 0, false));
            assertThat(coins.get(2).ranked()).isFalse();
        }
    }

    @Test
    void readsTheLogoOfOneCoinAndOnlyAnHttpsOne() {
        try (CoinPaprikaClient client = client()) {
            assertThat(client.logo("btc-bitcoin")).contains("https://static.coinpaprika.com/coin/btc-bitcoin/logo.png");
            assertThat(path.get()).isEqualTo("/v1/coins/btc-bitcoin");
            assertThat(client.logo("other")).isEmpty();
        }
    }

    @Test
    void aRateLimitIsCoinPaprikaAnswering() {
        status.set(429);
        try (CoinPaprikaClient client = client()) {
            assertThatThrownBy(client::coins).isInstanceOfSatisfying(CoinPaprikaException.class, e -> {
                assertThat(e.rateLimited()).isTrue();
                assertThat(e.serviceAnswered()).isTrue();
            });
        }
    }

    @Test
    void aServerErrorOrAnAnswerThatIsNotAListIsAFailure() {
        status.set(503);
        try (CoinPaprikaClient client = client()) {
            assertThatThrownBy(client::coins)
                    .isInstanceOfSatisfying(CoinPaprikaException.class, e -> assertThat(e.serviceAnswered())
                            .isFalse());
        }
        status.set(200);
        list.set("{\"not\":\"a list\"}");
        try (CoinPaprikaClient client = client()) {
            assertThatThrownBy(client::coins)
                    .isInstanceOfSatisfying(CoinPaprikaException.class, e -> assertThat(e.serviceAnswered())
                            .isFalse());
        }
        list.set("[{\"id\": \"broken\"");
        try (CoinPaprikaClient client = client()) {
            assertThatThrownBy(client::coins).isInstanceOf(CoinPaprikaException.class);
        }
    }

    @Test
    void aBodyThatStallsMidway_failsTheCallAtTheDeadline() {
        server.createContext("/v1/stalled", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("[{\"id\": \"btc-bitcoin\"".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            out.close();
        });
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/stalled");
        try (CoinPaprikaClient client = new CoinPaprikaClient(
                new CoinPaprikaProperties(
                        true,
                        base,
                        Duration.ZERO,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofMillis(500),
                        Duration.ofHours(24),
                        Map.of()),
                JsonMapper.builder().build())) {
            long started = System.nanoTime();

            assertThatThrownBy(client::tickers)
                    .isInstanceOfSatisfying(CoinPaprikaException.class, e -> assertThat(e.status())
                            .isZero());
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        }
    }

    @Test
    void noAnswerIsAFailure() {
        server.stop(0);
        try (CoinPaprikaClient client = client()) {
            assertThatThrownBy(client::coins)
                    .isInstanceOfSatisfying(CoinPaprikaException.class, e -> assertThat(e.status())
                            .isZero());
        }
    }

    @Test
    void overridesAreKeptByUpperCaseSymbol() {
        CoinPaprikaProperties properties = new CoinPaprikaProperties(
                true,
                URI.create("https://api.coinpaprika.com/v1"),
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofHours(24),
                Map.of(" ton ", " toncoin-the-open-network ", "X", " "));

        assertThat(properties.overrides()).isEqualTo(Map.of("TON", "toncoin-the-open-network"));
    }

    private CoinPaprikaClient client() {
        return client(Duration.ofSeconds(2));
    }

    private CoinPaprikaClient client(Duration body) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        return new CoinPaprikaClient(
                new CoinPaprikaProperties(
                        true,
                        base,
                        Duration.ZERO,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        body,
                        Duration.ofHours(24),
                        Map.of()),
                JsonMapper.builder().build());
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
