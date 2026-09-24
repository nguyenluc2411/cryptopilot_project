package com.cryptopilot.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.client.BinanceBanStore;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.client.BinanceClientProperties;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.BinanceVenue;
import com.cryptopilot.market.client.MarketInterval;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Binance IP ban outlives the process that received it (V7, TECHNICAL_DESIGN 7.1.2).
 *
 * <p>A restart is simulated the only way that is honest about what survives it: a first client receives
 * the 418 and is closed, and a second, new client over the same database is the application after the
 * restart. Nothing in memory is shared between them; only the {@code binance_ban} row is.
 *
 * <p>Bans grow with every repeat, from two minutes to three days (Binance Spot API documentation,
 * "LIMITS"), so the claim that matters is that the new client does not call the banned venue at all.
 *
 * <p>Rule: BR-09; TECHNICAL_DESIGN 7.1.1 and 7.1.2.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, BinanceBanPersistenceTest.TestClock.class})
class BinanceBanPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:30Z");

    @Autowired
    private BinanceBanStore bans;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private MutableTestClock clock;

    private HttpServer exchange;

    private final AtomicInteger spotCalls = new AtomicInteger();

    private final AtomicInteger futuresCalls = new AtomicInteger();

    @BeforeEach
    void startTheStandIn() throws Exception {
        clock.set(NOW);
        sql.sql("delete from binance_ban").update();
        exchange = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        exchange.createContext("/api/v3/klines", http -> {
            spotCalls.incrementAndGet();
            http.getResponseHeaders().add("Retry-After", "600");
            answer(http, 418, "{\"code\":-1003,\"msg\":\"banned\"}");
        });
        exchange.createContext("/fapi/v1/klines", http -> {
            futuresCalls.incrementAndGet();
            answer(http, 200, "[]");
        });
        exchange.start();
    }

    @AfterEach
    void stopTheStandIn() {
        exchange.stop(0);
        sql.sql("delete from binance_ban").update();
    }

    /** The ban is written before the refusal is returned, with the instant it ends and why. */
    @Test
    void BR09_a418_isWrittenToTheDatabaseBeforeTheRefusalReturns() {
        try (BinanceRestClient client = newClient()) {
            assertThat(refusalOf(client, BinanceVenue.SPOT).kind()).isEqualTo(BinanceClientException.Kind.BANNED);
        }

        assertThat(sql.sql("select banned_until from binance_ban where market_type = 'SPOT'")
                        .query(Instant.class)
                        .single())
                .isEqualTo(NOW.plusSeconds(600));
        assertThat(sql.sql("select reason from binance_ban where market_type = 'SPOT'")
                        .query(String.class)
                        .single())
                .contains("418");
    }

    /** After a restart the banned venue is refused without a single request, until the ban ends. */
    @Test
    void BR09_aBan_survivesARestart() {
        try (BinanceRestClient beforeRestart = newClient()) {
            refusalOf(beforeRestart, BinanceVenue.SPOT);
        }
        assertThat(spotCalls).hasValue(1);

        try (BinanceRestClient afterRestart = newClient()) {
            BinanceClientException refusal = refusalOf(afterRestart, BinanceVenue.SPOT);

            assertThat(refusal.kind()).isEqualTo(BinanceClientException.Kind.BANNED);
            assertThat(refusal.retryAt()).contains(NOW.plusSeconds(600));
            assertThat(spotCalls)
                    .as("the restarted client never called the banned venue")
                    .hasValue(1);
        }
    }

    /** A ban that ended while the application was down is ignored: the first call goes out. */
    @Test
    void BR09_anExpiredBan_isIgnoredAfterARestart() {
        bans.recordBan(BinanceVenue.SPOT, NOW.minusSeconds(1), "ended before this start");

        try (BinanceRestClient client = newClient()) {
            refusalOf(client, BinanceVenue.SPOT);
        }

        assertThat(spotCalls).as("the expired ban did not stop the call").hasValue(1);
    }

    /** A ban of Spot does not stop futures, before or after a restart. */
    @Test
    void BR09_aBanOfOneMarket_leavesTheOtherAlone() {
        try (BinanceRestClient beforeRestart = newClient()) {
            refusalOf(beforeRestart, BinanceVenue.SPOT);
        }

        try (BinanceRestClient afterRestart = newClient()) {
            assertThat(afterRestart.klines(
                            BinanceVenue.USD_M_FUTURES, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1))
                    .isEmpty();
        }

        assertThat(futuresCalls).hasValue(1);
        assertThat(bans.bannedUntil(BinanceVenue.USD_M_FUTURES)).isEmpty();
    }

    /** One row per market: a later ban replaces the earlier one rather than adding a row. */
    @Test
    void BR09_aLaterBan_replacesTheEarlierOne() {
        bans.recordBan(BinanceVenue.USD_M_FUTURES, NOW.plusSeconds(120), "first");
        bans.recordBan(BinanceVenue.USD_M_FUTURES, NOW.plusSeconds(3600), "x".repeat(600));

        assertThat(bans.bannedUntil(BinanceVenue.USD_M_FUTURES)).contains(NOW.plusSeconds(3600));
        assertThat(sql.sql("select count(*) from binance_ban")
                        .query(Integer.class)
                        .single())
                .isOne();
        assertThat(sql.sql("select length(reason) from binance_ban")
                        .query(Integer.class)
                        .single())
                .as("a long reason is cut to the column, not refused")
                .isEqualTo(500);
    }

    private BinanceRestClient newClient() {
        URI base = URI.create("http://127.0.0.1:" + exchange.getAddress().getPort());
        return new BinanceRestClient(
                new BinanceClientProperties(
                        new BinanceClientProperties.Venue(base, 6000),
                        new BinanceClientProperties.Venue(base, 2400),
                        Duration.ofMillis(500),
                        Duration.ofMillis(500),
                        80,
                        Duration.ofMinutes(2),
                        new BinanceClientProperties.Retry(
                                0, Duration.ofMillis(1), 1.0, Duration.ofMillis(1), Duration.ZERO),
                        new BinanceClientProperties.CircuitBreaker(5, Duration.ofSeconds(30))),
                clock,
                JsonMapper.builder().build(),
                bans);
    }

    private static BinanceClientException refusalOf(BinanceRestClient client, BinanceVenue venue) {
        try {
            client.klines(venue, "BTCUSDT", MarketInterval.ONE_MINUTE, null, null, 1);
        } catch (BinanceClientException refusal) {
            return refusal;
        }
        throw new AssertionError("the call was expected to be refused");
    }

    private static void answer(HttpExchange http, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        http.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = http.getResponseBody()) {
            out.write(bytes);
        }
    }

    @TestConfiguration
    static class TestClock {

        @Bean
        MutableTestClock testClock() {
            return new MutableTestClock(NOW);
        }

        @Bean
        @Primary
        Clock clockUnderTest(MutableTestClock testClock) {
            return testClock;
        }
    }
}
