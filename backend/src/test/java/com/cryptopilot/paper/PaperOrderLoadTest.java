package com.cryptopilot.paper;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.paper.service.PaperAccountService;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * How many Traders can place a paper order at the same instant, over real HTTP: the embedded Tomcat on virtual
 * threads, the Hikari pool, PostgreSQL and Redis in containers. Each simulated Trader has an account of their own and
 * places one Spot MARKET buy; the waves grow until requests fail or the slowest answer passes {@link #SLOW}. A second
 * part keeps a number of Traders placing back to back for a while, for the sustained rate.
 *
 * <p>Tagged {@code load} and excluded from the default build: it takes minutes, measures this machine rather than a
 * deployment, and the database shares the CPU with the application. Run it on purpose with
 * {@code ./mvnw test -Dtest=PaperOrderLoadTest -Dsurefire.excludedGroups=none}. It writes rows into the throw-away
 * test database and does not remove them.
 *
 * <p>Rule: TR-02.
 */
@Tag("load")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class PaperOrderLoadTest {

    private static final List<Integer> WAVES = sizes("load.waves", "100,250,500,1000,2000,3000,5000");
    private static final List<Integer> SUSTAINED = sizes("load.sustained", "50,200,500");
    private static final Duration SUSTAIN_FOR = Duration.ofSeconds(Long.getLong("load.seconds", 15));
    private static final String BUY = "\"side\": \"BUY\", \"quantity\": 0.06";
    /** What a buy brought in, less its 0.1% fee, so the sale never asks for more than the Trader holds. */
    private static final String SELL = "\"side\": \"SELL\", \"quantity\": 0.059";

    private static final Duration SLOW = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private PriceCacheService prices;

    @Autowired
    private PaperAccountService accounts;

    private final HttpClient http = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private UUID pair;
    private String symbol;

    @Test
    void howManyTradersCanPlaceAtOnce() throws Exception {
        symbol = "LD" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, false, "TRADING", null, 0);
        sql.sql("""
                        update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.001, spot_min_notional = 5
                         where pair_id = ?""").param(pair).update();
        // NSF-03 refuses a price that is not current, so the ticker keeps coming while the Traders place.
        Thread ticker = Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                price("100");
                try {
                    Thread.sleep(500);
                } catch (InterruptedException stopped) {
                    return;
                }
            }
        });
        try {
            measure();
        } finally {
            ticker.interrupt();
        }
        assertThat(sql.sql("select count(*) from paper_fill where pair_id = ?")
                        .param(pair)
                        .query(Long.class)
                        .single())
                .isPositive();
    }

    private void measure() throws Exception {
        int most = Math.max(WAVES.isEmpty() ? 0 : WAVES.getLast(), SUSTAINED.isEmpty() ? 0 : SUSTAINED.getLast());
        System.out.printf("%nPreparing %d Traders with opened accounts...%n", most);
        List<String> tokens = traders(most);

        // Warm-up: JIT, pool, prepared statements.
        wave(tokens.subList(0, Math.min(50, tokens.size())));

        System.out.println();
        System.out.println("BURST: N Traders send one MARKET buy at the same instant");
        System.out.println("     N |  ok | fail |  p50 ms |  p95 ms |  p99 ms |  max ms | all done in ms");
        for (int size : WAVES) {
            Result result = wave(tokens.subList(0, size));
            System.out.printf(
                    "%6d | %3s | %4d | %7d | %7d | %7d | %7d | %d%n",
                    size,
                    result.failures() == 0 ? "yes" : "no",
                    result.failures(),
                    result.percentile(50),
                    result.percentile(95),
                    result.percentile(99),
                    result.percentile(100),
                    result.wallMillis());
            if (result.failures() > 0 || result.percentile(100) > SLOW.toMillis()) {
                System.out.println("       stopped: failures or an answer slower than " + SLOW.toSeconds() + " s");
                break;
            }
        }

        System.out.println();
        System.out.println("SUSTAINED: N Traders place back to back for " + SUSTAIN_FOR.toSeconds() + " s");
        System.out.println("     N | orders | orders/s | fail |  p50 ms |  p95 ms |  p99 ms |  max ms");
        for (int size : SUSTAINED) {
            Result result = sustained(tokens.subList(0, size));
            System.out.printf(
                    "%6d | %6d | %8.0f | %4d | %7d | %7d | %7d | %7d%n",
                    size,
                    result.latencies().size(),
                    result.latencies().size() * 1000.0 / result.wallMillis(),
                    result.failures(),
                    result.percentile(50),
                    result.percentile(95),
                    result.percentile(99),
                    result.percentile(100));
        }
        System.out.println();
    }

    /** Every Trader sends one order, all released by one latch. */
    private Result wave(List<String> tokens) throws Exception {
        CountDownLatch ready = new CountDownLatch(tokens.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong failures = new AtomicLong();
        List<Future<?>> sent = new ArrayList<>();
        long wallStart;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String token : tokens) {
                sent.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    send(token, BUY, latencies, failures);
                    return null;
                }));
            }
            ready.await(60, TimeUnit.SECONDS);
            wallStart = System.nanoTime();
            go.countDown();
            for (Future<?> one : sent) {
                one.get(5, TimeUnit.MINUTES);
            }
        }
        return new Result(latencies, failures.get(), (System.nanoTime() - wallStart) / 1_000_000);
    }

    /** Each Trader places, waits for the answer and places again, until the time is up. */
    private Result sustained(List<String> tokens) throws Exception {
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong failures = new AtomicLong();
        AtomicBoolean going = new AtomicBoolean(true);
        long wallStart = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String token : tokens) {
                pool.submit(() -> {
                    // Buying and selling in turn, so a Trader's funds last however long it runs.
                    boolean buy = true;
                    while (going.get()) {
                        send(token, buy ? BUY : SELL, latencies, failures);
                        buy = !buy;
                    }
                    return null;
                });
            }
            Thread.sleep(SUSTAIN_FOR);
            going.set(false);
        }
        return new Result(latencies, failures.get(), (System.nanoTime() - wallStart) / 1_000_000);
    }

    private void send(String token, String order, List<Long> latencies, AtomicLong failures) {
        String body = "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"type\": \"MARKET\", " + order + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/paper/orders"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            latencies.add((System.nanoTime() - start) / 1_000_000);
            if (response.statusCode() != 201) {
                failures.incrementAndGet();
            }
        } catch (Exception failed) {
            latencies.add((System.nanoTime() - start) / 1_000_000);
            failures.incrementAndGet();
        }
    }

    /** Traders with opened accounts, as bearer tokens. */
    private List<String> traders(int count) throws Exception {
        List<String> tokens = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < count; i++) {
                pool.submit(() -> {
                    UUID id = UUID.randomUUID();
                    Timestamp now = Timestamp.from(Instant.now());
                    sql.sql("""
                                    insert into user_account (user_id, email, password_hash, role, account_status,
                                                              created_at, updated_at)
                                    values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@load.invalid", now, now).update();
                    accounts.open(id);
                    tokens.add(bearer(id));
                    return null;
                });
            }
        }
        return new ArrayList<>(tokens);
    }

    /** A Spot ticker of the pair in the cache, fresh enough to be the current price. */
    private void price(String last) {
        BigDecimal value = new BigDecimal(last);
        prices.record(
                MarketType.SPOT,
                new TickerMessage(
                        symbol,
                        value,
                        value,
                        value,
                        value,
                        value,
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        Instant.now()));
        prices.flush();
    }

    private String bearer(UUID userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(2)))
                .claim(JwtConfig.ROLE_CLAIM, UserRole.TRADER.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }

    /** Sizes from a system property, e.g. {@code -Dload.waves=100,1000}; none when it is empty. */
    private static List<Integer> sizes(String property, String fallback) {
        String value = System.getProperty(property, fallback);
        return value.isBlank()
                ? List.of()
                : java.util.Arrays.stream(value.split(","))
                        .map(String::trim)
                        .map(Integer::valueOf)
                        .toList();
    }

    private record Result(List<Long> latencies, long failures, long wallMillis) {

        long percentile(int p) {
            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            if (sorted.isEmpty()) {
                return 0;
            }
            int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }
    }
}
