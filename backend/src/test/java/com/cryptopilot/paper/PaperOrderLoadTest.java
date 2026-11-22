package com.cryptopilot.paper;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.OrderBookDepth;
import com.cryptopilot.market.OrderBookDepth.PriceLevel;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.market.service.impl.TradingMarketData;
import com.cryptopilot.paper.job.PaperMatchingWorker;
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
 * <p>{@link #restingOrdersFilledByOneTrade} is TR-32's case: {@code load.resting.traders} Traders (500) each rest
 * {@code load.resting.orders} LIMIT buys (20) over real HTTP, then one market trade through every price fills them all
 * through the matching worker. It prints the placement latency end to end and, from the rows, each fill's latency from
 * the trade to its commit, against plan section 12's targets: placement p95 under 300 ms, fill p95 under 1 s.
 *
 * <p>Rule: TR-02, TR-32.
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

    @Autowired
    private PaperMatchingWorker worker;

    @Autowired
    private TradingMarketData trading;

    private final HttpClient http = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private UUID pair;
    private String symbol;

    /** What the refused requests answered, by status and code, for the report. */
    private final java.util.Map<String, Long> refusals = new java.util.concurrent.ConcurrentHashMap<>();

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
        assertThat(sql.sql("select count(*) from order_fill where pair_id = ?")
                        .param(pair)
                        .query(Long.class)
                        .single())
                .isPositive();
    }

    /** TR-32: many resting orders, then one trade through all of them; placement and fill latency. */
    @Test
    void restingOrdersFilledByOneTrade() throws Exception {
        int traderCount = Integer.getInteger("load.resting.traders", 500);
        int perTrader = Integer.getInteger("load.resting.orders", 20);
        // A first, small round warms the JIT, the pool and the prepared statements: a cold JVM spends most of a
        // fill's time interpreting.
        restingRound(Math.min(10, traderCount), perTrader, "WARM-UP");
        restingRound(traderCount, perTrader, "MEASURED");
    }

    private void restingRound(int traderCount, int perTrader, String label) throws Exception {
        symbol = "LR" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, false, "TRADING", null, 0);
        sql.sql("""
                        update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.001, spot_min_notional = 5
                         where pair_id = ?""").param(pair).update();
        // The book a LIMIT buy is checked against on arrival (BR-71): asks above every resting price, so each rests.
        AtomicLong depthId = new AtomicLong();
        Thread ticker = Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                price("100");
                trading.recordDepth(new OrderBookDepth(
                        MarketType.SPOT,
                        pair,
                        depthId.incrementAndGet(),
                        List.of(new PriceLevel(new BigDecimal("89"), new BigDecimal("1000"))),
                        List.of(new PriceLevel(new BigDecimal("100"), new BigDecimal("1000"))),
                        Instant.now()));
                try {
                    Thread.sleep(500);
                } catch (InterruptedException stopped) {
                    return;
                }
            }
        });
        try {
            System.out.printf("%nPreparing %d Traders with opened accounts...%n", traderCount);
            // The first opening stores the coins of the grant; the others then open side by side without racing for
            // them.
            List<String> tokens = new ArrayList<>(traders(1));
            tokens.addAll(traders(traderCount - 1));
            List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
            AtomicLong failures = new AtomicLong();
            long wallStart = System.nanoTime();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (String token : tokens) {
                    pool.submit(() -> {
                        for (int i = 0; i < perTrader; i++) {
                            String price = new BigDecimal("90")
                                    .add(new BigDecimal("0.5").multiply(BigDecimal.valueOf(i)))
                                    .toPlainString();
                            send(
                                    token,
                                    "\"type\": \"LIMIT\", \"timeInForce\": \"GTC\", \"price\": " + price
                                            + ", \"side\": \"BUY\", \"quantity\": 0.06",
                                    latencies,
                                    failures);
                        }
                        return null;
                    });
                }
            }
            Result placed = new Result(latencies, failures.get(), (System.nanoTime() - wallStart) / 1_000_000);
            long resting = sql.sql("select count(*) from trade_order where pair_id = ? and order_status = 'NEW'")
                    .param(pair)
                    .query(Long.class)
                    .single();
            System.out.println();
            System.out.println("TR-32 RESTING (" + label + "): " + traderCount + " Traders x " + perTrader
                    + " LIMIT buys over HTTP");
            System.out.println(" orders | fail | resting |  p50 ms |  p95 ms |  p99 ms |  max ms | all placed in ms");
            System.out.printf(
                    "%7d | %4d | %7d | %7d | %7d | %7d | %7d | %d%n",
                    latencies.size(),
                    placed.failures(),
                    resting,
                    placed.percentile(50),
                    placed.percentile(95),
                    placed.percentile(99),
                    placed.percentile(100),
                    placed.wallMillis());

            // Give the worker the time to track every order the commits published.
            Thread.sleep(2_000);
            Instant traded = Instant.now();
            long fillStart = System.nanoTime();
            worker.onTrade(new MarketTrade(
                    MarketType.SPOT,
                    pair,
                    1,
                    new BigDecimal("89.99"),
                    new BigDecimal("1000000"),
                    traded,
                    false,
                    traded));
            // Each fill's latency, from the trade to the moment its commit is seen: the count of FILLED orders is read
            // every 20 ms, and each newly filled order is timed at the read that first saw it.
            List<Long> fillLatencies = new ArrayList<>();
            long filled = 0;
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (System.nanoTime() < deadline && filled < resting) {
                long now = sql.sql("select count(*) from trade_order where pair_id = ? and order_status = 'FILLED'")
                        .param(pair)
                        .query(Long.class)
                        .single();
                long elapsed = (System.nanoTime() - fillStart) / 1_000_000;
                for (long seen = filled; seen < now; seen++) {
                    fillLatencies.add(elapsed);
                }
                filled = now;
                Thread.sleep(20);
            }
            long allFilledMillis = (System.nanoTime() - fillStart) / 1_000_000;
            Result fills = new Result(fillLatencies, resting - filled, allFilledMillis);
            System.out.println("refused: " + refusals);
            System.out.println();
            System.out.println("TR-32 FILL (" + label
                    + "): one trade at 89.99 through every resting buy (latency seen every 20 ms)");
            System.out.println(" filled | left |  p50 ms |  p95 ms |  p99 ms |  max ms | all filled in ms");
            System.out.printf(
                    "%7d | %4d | %7d | %7d | %7d | %7d | %d%n",
                    filled,
                    fills.failures(),
                    fills.percentile(50),
                    fills.percentile(95),
                    fills.percentile(99),
                    fills.percentile(100),
                    allFilledMillis);
            System.out.printf(
                    "Targets (plan 12): placement p95 < 300 ms: %s; fill p95 < 1000 ms: %s%n%n",
                    placed.percentile(95) < 300 ? "met" : "NOT met", fills.percentile(95) < 1000 ? "met" : "NOT met");
            assertThat(filled).isEqualTo(resting);
        } finally {
            ticker.interrupt();
        }
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
        String body = order.contains("\"type\"")
                ? "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", " + order + "}"
                : "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"type\": \"MARKET\", " + order + "}";
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
                refusals.merge(response.statusCode() + " " + code(response.body()), 1L, Long::sum);
            }
        } catch (Exception failed) {
            latencies.add((System.nanoTime() - start) / 1_000_000);
            failures.incrementAndGet();
            refusals.merge(failed.getClass().getSimpleName(), 1L, Long::sum);
        }
    }

    /** The {@code code} of a problem detail, or nothing. */
    private static String code(String body) {
        int at = body == null ? -1 : body.indexOf("\"code\":\"");
        return at < 0 ? "" : body.substring(at + 8, Math.max(at + 8, body.indexOf('"', at + 8)));
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
