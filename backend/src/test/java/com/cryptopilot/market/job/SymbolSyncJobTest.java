package com.cryptopilot.market.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceClientProperties;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.InMemoryBinanceBans;
import com.cryptopilot.market.client.StubExchange;
import com.cryptopilot.market.client.StubExchange.Answer;
import com.cryptopilot.market.config.SymbolSyncProperties;
import com.cryptopilot.market.entity.ExchangeStatus;
import com.cryptopilot.market.job.SymbolSyncJob.Outcome;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.service.ExchangeInfoFixtures;
import com.cryptopilot.market.service.SymbolSyncService;
import com.cryptopilot.market.service.SymbolSyncWriter;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * The NSF-01 job honouring the Binance client's caller contract: each kind of refusal leads to its own
 * scheduling outcome, and no refusal ever holds the scheduler's thread.
 *
 * <p>The refusals are real: a local stand-in exchange answers 429, 418, 5xx, 400 and a malformed body, and the
 * real client turns each into its kind. The scheduler is a recording stand-in, so a test sees exactly which
 * tasks were handed to it and for when — a retry is a task scheduled at {@code retryAt}, never a sleep.
 *
 * <p>Rule: NSF-01; BR-09; TECHNICAL_DESIGN 7.1.2 (the caller contract).
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class SymbolSyncJobTest {

    private static final Instant NOW = Instant.parse("2026-09-24T00:05:00Z");

    @Autowired
    private SymbolSyncWriter writer;

    @Autowired
    private CryptoPairRepository pairs;

    @Autowired
    private JdbcClient sql;

    private final MutableTestClock clock = new MutableTestClock(NOW);

    private final List<Scheduled> scheduled = new ArrayList<>();

    private final List<Boolean> cancelled = new ArrayList<>();

    private StubExchange exchange;

    private BinanceRestClient client;

    @BeforeEach
    void startTheStandIn() throws Exception {
        exchange = new StubExchange();
        client = clientFor(exchange.baseUrl(), 5);
        exchange.on(
                ExchangeInfoFixtures.SPOT_PATH,
                Answer.ok(ExchangeInfoFixtures.spot().json()));
        exchange.on(
                ExchangeInfoFixtures.FUTURES_PATH,
                Answer.ok(ExchangeInfoFixtures.futures().json()));
        registerBtcUsdt();
    }

    @AfterEach
    void stopEverything() {
        client.close();
        exchange.close();
        sql.sql("delete from crypto_pair").update();
        sql.sql("delete from coin").update();
    }

    @Test
    void NSF01_aSuccessfulRun_isSynced() {
        assertThat(job(true).run(MarketType.SPOT)).isEqualTo(Outcome.SYNCED);
        assertThat(scheduled).isEmpty();
    }

    /** RATE_LIMITED: the run ends and one run is scheduled at the exchange's Retry-After. */
    @Test
    void NSF01_aRateLimit_schedulesOneRetryAtRetryAfter() {
        exchange.on(ExchangeInfoFixtures.SPOT_PATH, Answer.status(429).withHeader("Retry-After", "30"));

        assertThat(job(true).run(MarketType.SPOT)).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(scheduled).singleElement().satisfies(task -> assertThat(task.at())
                .isEqualTo(NOW.plusSeconds(30)));
    }

    /** BANNED: the retry waits for the end of the ban. */
    @Test
    void NSF01_aBan_schedulesOneRetryAtTheEndOfTheBan() {
        exchange.on(ExchangeInfoFixtures.FUTURES_PATH, Answer.status(418).withHeader("Retry-After", "7200"));

        assertThat(job(true).run(MarketType.FUTURES)).isEqualTo(Outcome.RETRY_SCHEDULED);

        assertThat(scheduled).singleElement().satisfies(task -> assertThat(task.at())
                .isEqualTo(NOW.plusSeconds(7200)));
    }

    /**
     * UNAVAILABLE waits for the next scheduled run; once the breaker is open, CIRCUIT_OPEN schedules a retry at
     * the end of the open period.
     */
    @Test
    void NSF01_anOutageWaits_andAnOpenCircuitSchedulesARetry() {
        client.close();
        client = clientFor(exchange.baseUrl(), 1);
        exchange.on(ExchangeInfoFixtures.SPOT_PATH, Answer.status(503));
        SymbolSyncJob job = job(true);

        assertThat(job.run(MarketType.SPOT)).isEqualTo(Outcome.WAIT_FOR_NEXT_RUN);
        assertThat(scheduled).as("an outage schedules nothing").isEmpty();

        assertThat(job.run(MarketType.SPOT)).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(scheduled).singleElement().satisfies(task -> assertThat(task.at())
                .isEqualTo(NOW.plusSeconds(30)));
    }

    /** REJECTED and MALFORMED are defects: nothing is scheduled, because waiting will not fix them. */
    @Test
    void NSF01_aRejectionOrAMalformedBody_isADefectAndSchedulesNothing() {
        exchange.on(ExchangeInfoFixtures.SPOT_PATH, Answer.status(400));
        exchange.on(ExchangeInfoFixtures.FUTURES_PATH, Answer.ok("{\"symbols\":\"not a list\"}"));
        SymbolSyncJob job = job(true);

        assertThat(job.run(MarketType.SPOT)).isEqualTo(Outcome.DEFECT);
        assertThat(job.run(MarketType.FUTURES)).isEqualTo(Outcome.DEFECT);
        assertThat(scheduled).isEmpty();
    }

    /** A second refusal replaces the pending retry of that market instead of adding a second one. */
    @Test
    void NSF01_aNewRefusal_replacesThePendingRetry() {
        exchange.on(ExchangeInfoFixtures.SPOT_PATH, Answer.status(429).withHeader("Retry-After", "30"));
        SymbolSyncJob job = job(true);

        job.run(MarketType.SPOT);
        clock.advance(Duration.ofSeconds(30));
        job.run(MarketType.SPOT);

        assertThat(scheduled).hasSize(2);
        assertThat(cancelled).containsExactly(true);
    }

    /** One market failing does not stop the other: Spot is synced while futures waits. */
    @Test
    void NSF01_runAll_synchronisesEachMarketOnItsOwn() {
        exchange.on(ExchangeInfoFixtures.FUTURES_PATH, Answer.status(503));

        job(true).runAll();

        assertThat(pairs.findBySymbol("BTCUSDT").orElseThrow().exchangeStatus(MarketType.SPOT))
                .isEqualTo(ExchangeStatus.TRADING);
        assertThat(pairs.findBySymbol("BTCUSDT").orElseThrow().exchangeStatus(MarketType.FUTURES))
                .isNull();
    }

    /** An unexpected failure is logged and waits for the next run; it does not escape into the scheduler. */
    @Test
    void NSF01_anUnexpectedFailure_waitsForTheNextRun() {
        SymbolSyncJob broken = new SymbolSyncJob(null, recordingScheduler(), properties(true), clock);

        assertThat(broken.run(MarketType.SPOT)).isEqualTo(Outcome.WAIT_FOR_NEXT_RUN);
    }

    /** NSF-01, "at start-up and daily at 00:05 UTC": one run now and the cron in the configured zone. */
    @Test
    void NSF01_start_schedulesARunNowAndTheDailyCron() {
        job(true).start();

        assertThat(scheduled).hasSize(2);
        assertThat(scheduled.get(0).at()).isEqualTo(NOW);
        assertThat(scheduled.get(1).trigger()).isInstanceOfSatisfying(CronTrigger.class, cron -> {
            assertThat(cron.getExpression()).isEqualTo("0 5 0 * * *");
            assertThat(cron.toString()).isEqualTo("0 5 0 * * *");
        });
    }

    /** Disabled — as in every context without the dev or prod profile — nothing is scheduled at all. */
    @Test
    void NSF01_aDisabledJob_schedulesNothing() {
        job(false).start();

        assertThat(scheduled).isEmpty();
    }

    private SymbolSyncJob job(boolean enabled) {
        SymbolSyncProperties properties = properties(enabled);
        return new SymbolSyncJob(
                new SymbolSyncService(client, writer, properties, clock), recordingScheduler(), properties, clock);
    }

    private static SymbolSyncProperties properties(boolean enabled) {
        return new SymbolSyncProperties(enabled, "0 5 0 * * *", ZoneOffset.UTC, List.of());
    }

    /** A task handed to the scheduler: for an instant, or on a trigger. */
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
                    if (method.getName().equals("getClock")) {
                        return clock;
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

    private void registerBtcUsdt() {
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
                                                 created_at, updated_at)
                        values (?, ?, ?, 'BTCUSDT', 'INACTIVE', ?, ?)""").params(UUID.randomUUID(), btc, usdt, at, at).update();
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
