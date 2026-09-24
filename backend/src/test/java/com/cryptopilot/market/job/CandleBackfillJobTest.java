package com.cryptopilot.market.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceClientProperties;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.InMemoryBinanceBans;
import com.cryptopilot.market.client.StubExchange;
import com.cryptopilot.market.client.StubExchange.Answer;
import com.cryptopilot.market.config.CandleBackfillProperties;
import com.cryptopilot.market.event.SymbolsSynchronised;
import com.cryptopilot.market.job.CandleBackfillJob.Outcome;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.market.service.CandleBackfillService;
import com.cryptopilot.market.service.SyntheticKlines;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The NSF-02 job: each outcome of a backfill run and each refusal of the exchange leads to its scheduling
 * outcome — a continuation handed to the scheduler for an instant, or nothing until the next run — and the
 * scheduler's thread is never held.
 *
 * <p>Rule: NSF-02; TECHNICAL_DESIGN 7.1.2 (the caller contract); D-41.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class CandleBackfillJobTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:07:30Z");
    private static final String KLINES = "/api/v3/klines";

    @Autowired
    private CryptoPairRepository pairs;

    @Autowired
    private OhlcvRepository candles;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcClient sql;

    private final MutableTestClock clock = new MutableTestClock(NOW);
    private final SyntheticKlines exchangeKlines = new SyntheticKlines(NOW);
    private final List<Scheduled> scheduled = new ArrayList<>();
    private final List<Boolean> cancelled = new ArrayList<>();

    private StubExchange exchange;
    private BinanceRestClient client;

    @BeforeEach
    void startTheStandIn() throws Exception {
        exchange = new StubExchange();
        exchange.respond(KLINES, exchangeKlines::answer);
        client = clientFor(exchange.baseUrl(), 5);
        tradingPair();
    }

    @AfterEach
    void stopEverything() {
        client.close();
        exchange.close();
        sql.sql("delete from ohlcv").update();
        sql.sql("delete from crypto_pair").update();
        sql.sql("delete from coin").update();
    }

    @Test
    void NSF02_aFinishedRun_isCompletedAndSchedulesNothing() {
        assertThat(job(true).run(MarketType.SPOT)).isEqualTo(Outcome.COMPLETED);
        assertThat(scheduled).isEmpty();
    }

    /** Paused at the weight share: continued at the next minute, without holding the thread. */
    @Test
    void NSF02_aPausedRun_isContinuedAtTheNextMinute() {
        exchangeKlines.usedWeight(3000);

        assertThat(job(true).run(MarketType.SPOT)).isEqualTo(Outcome.CONTINUATION_SCHEDULED);

        assertThat(scheduled).singleElement().satisfies(task -> assertThat(task.at())
                .isEqualTo(Instant.parse("2026-09-24T10:08:00Z")));
    }

    /** RATE_LIMITED and BANNED: continued at the exchange's retryAt. */
    @Test
    void NSF02_aRateLimitOrABan_isContinuedAtRetryAt() {
        exchange.on(KLINES, Answer.status(429).withHeader("Retry-After", "30"));
        assertThat(job(true).run(MarketType.SPOT)).isEqualTo(Outcome.CONTINUATION_SCHEDULED);

        client.close();
        client = clientFor(exchange.baseUrl(), 5);
        exchange.on(KLINES, Answer.status(418).withHeader("Retry-After", "7200"));
        assertThat(job(true).run(MarketType.SPOT)).isEqualTo(Outcome.CONTINUATION_SCHEDULED);

        assertThat(scheduled).extracting(Scheduled::at).containsExactly(NOW.plusSeconds(30), NOW.plusSeconds(7200));
    }

    /** UNAVAILABLE waits for the next run; the open circuit that follows is continued at its end. */
    @Test
    void NSF02_anOutageWaits_andAnOpenCircuitIsContinuedAtItsEnd() {
        client.close();
        client = clientFor(exchange.baseUrl(), 1);
        exchange.on(KLINES, Answer.status(503));
        CandleBackfillJob job = job(true);

        assertThat(job.run(MarketType.SPOT)).isEqualTo(Outcome.WAIT_FOR_NEXT_RUN);
        assertThat(scheduled).isEmpty();
        assertThat(job.run(MarketType.SPOT)).isEqualTo(Outcome.CONTINUATION_SCHEDULED);
        assertThat(scheduled).singleElement().satisfies(task -> assertThat(task.at())
                .isEqualTo(NOW.plusSeconds(30)));
    }

    /** A newer continuation replaces the pending one of that market. */
    @Test
    void NSF02_aNewContinuation_replacesThePendingOne() {
        exchangeKlines.usedWeight(3000);
        CandleBackfillJob job = job(true);

        job.run(MarketType.SPOT);
        job.run(MarketType.SPOT);

        assertThat(scheduled).hasSize(2);
        assertThat(cancelled).containsExactly(true);
    }

    /** An unexpected failure waits for the next run rather than escaping into the scheduler. */
    @Test
    void NSF02_anUnexpectedFailure_waitsForTheNextRun() {
        CandleBackfillJob broken = new CandleBackfillJob(null, recordingScheduler(), properties(true), clock);

        assertThat(broken.run(MarketType.SPOT)).isEqualTo(Outcome.WAIT_FOR_NEXT_RUN);
    }

    /** After a symbol synchronisation of a market, that market's backfill is scheduled at once. */
    @Test
    void NSF02_aSymbolSynchronisation_triggersThatMarketsBackfill() {
        job(true).onSymbolsSynchronised(new SymbolsSynchronised(MarketType.FUTURES));

        assertThat(scheduled).singleElement().satisfies(task -> assertThat(task.at())
                .isEqualTo(NOW));
    }

    /** Enabled, the hourly cron is registered; runAll backfills both markets. */
    @Test
    void NSF02_start_registersTheHourlyCron() {
        CandleBackfillJob job = job(true);

        job.start();
        job.runAll();

        assertThat(scheduled).singleElement().satisfies(task -> assertThat(task.trigger())
                .isInstanceOfSatisfying(CronTrigger.class, cron -> assertThat(cron.getExpression())
                        .isEqualTo("0 1 * * * *")));
        assertThat(exchange.hits(KLINES)).isPositive();
    }

    /** Disabled, as without the dev or prod profile, nothing is scheduled, not even after a synchronisation. */
    @Test
    void NSF02_aDisabledJob_schedulesNothing() {
        CandleBackfillJob job = job(false);

        job.start();
        job.onSymbolsSynchronised(new SymbolsSynchronised(MarketType.SPOT));

        assertThat(scheduled).isEmpty();
    }

    private CandleBackfillJob job(boolean enabled) {
        CandleBackfillProperties properties = properties(enabled);
        return new CandleBackfillJob(
                new CandleBackfillService(client, pairs, candles, properties, transactions, clock),
                recordingScheduler(),
                properties,
                clock);
    }

    private static CandleBackfillProperties properties(boolean enabled) {
        return new CandleBackfillProperties(
                enabled,
                "0 1 * * * *",
                ZoneOffset.UTC,
                50,
                new CandleBackfillProperties.Depth(
                        Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(5), Duration.ofDays(10)),
                new CandleBackfillProperties.PageSize(50, 20));
    }

    private record Scheduled(Runnable task, Instant at, Trigger trigger) {}

    private TaskScheduler recordingScheduler() {
        return (TaskScheduler) Proxy.newProxyInstance(
                TaskScheduler.class.getClassLoader(), new Class<?>[] {TaskScheduler.class}, (proxy, method, args) -> {
                    if (method.getName().equals("schedule") && args[1] instanceof Instant at) {
                        scheduled.add(new Scheduled((Runnable) args[0], at, null));
                        return future();
                    }
                    if (method.getName().equals("schedule") && args[1] instanceof Trigger trigger) {
                        scheduled.add(new Scheduled((Runnable) args[0], null, trigger));
                        return future();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private ScheduledFuture<?> future() {
        return (ScheduledFuture<?>) Proxy.newProxyInstance(
                ScheduledFuture.class.getClassLoader(),
                new Class<?>[] {ScheduledFuture.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("cancel")) {
                        cancelled.add(true);
                        return true;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private void tradingPair() {
        OffsetDateTime at = NOW.atOffset(ZoneOffset.UTC);
        UUID btc = UUID.randomUUID();
        UUID usdt = UUID.randomUUID();
        for (Object[] coin : new Object[][] {{btc, "BTC"}, {usdt, "USDT"}}) {
            sql.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                    .params(coin[0], coin[1], coin[1], at, at)
                    .update();
        }
        sql.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, pair_status,
                                                 spot_exchange_status, created_at, updated_at)
                        values (?, ?, ?, 'BTCUSDT', 'INACTIVE', 'TRADING', ?, ?)""").params(UUID.randomUUID(), btc, usdt, at, at).update();
    }

    private BinanceRestClient clientFor(URI base, int failureThreshold) {
        return new BinanceRestClient(
                new BinanceClientProperties(
                        new BinanceClientProperties.Venue(base, 6000),
                        new BinanceClientProperties.Venue(base, 2400),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(2),
                        80,
                        Duration.ofMinutes(2),
                        new BinanceClientProperties.Retry(
                                0, Duration.ofMillis(1), 1.0, Duration.ofMillis(1), Duration.ZERO),
                        new BinanceClientProperties.CircuitBreaker(failureThreshold, Duration.ofSeconds(30))),
                clock,
                JsonMapper.builder().build(),
                new InMemoryBinanceBans());
    }
}
