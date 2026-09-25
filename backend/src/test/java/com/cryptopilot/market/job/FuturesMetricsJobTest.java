package com.cryptopilot.market.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.client.BinanceClientProperties;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.InMemoryBinanceBans;
import com.cryptopilot.market.client.StubExchange;
import com.cryptopilot.market.client.StubExchange.Answer;
import com.cryptopilot.market.config.FuturesMetricsProperties;
import com.cryptopilot.market.job.FuturesMetricsJob.Outcome;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.FuturesMetricsRepository;
import com.cryptopilot.market.service.FuturesMetricsService;
import com.cryptopilot.market.service.LatestMarketData;
import com.cryptopilot.market.service.impl.FuturesMetricsServiceImpl;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
 * The NSF-04 job: each outcome of a run and each refusal of the exchange leads to its scheduling outcome — a
 * run again at the exchange's instant, or nothing until the next run — runs never overlap, and the scheduler's
 * thread is never held.
 *
 * <p>Rule: NSF-04; TECHNICAL_DESIGN 7.1.2 (the caller contract).
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class FuturesMetricsJobTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:06:30Z");
    private static final String FUNDING_INFO = "/fapi/v1/fundingInfo";

    @Autowired
    private CryptoPairRepository pairs;

    @Autowired
    private FuturesMetricsRepository metrics;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcClient sql;

    private final MutableTestClock clock = new MutableTestClock(NOW);
    private final List<Scheduled> scheduled = new ArrayList<>();
    private final List<Boolean> cancelled = new ArrayList<>();

    private MarketTestData data;
    private StubExchange exchange;
    private BinanceRestClient client;

    @BeforeEach
    void startTheStandIn() throws Exception {
        data = new MarketTestData(sql, NOW);
        exchange = new StubExchange();
        exchange.on(FUNDING_INFO, Answer.ok("[]"));
        exchange.on("/fapi/v1/premiumIndex", Answer.ok("""
                {"symbol":"BTCUSDT","markPrice":"1","indexPrice":"1","lastFundingRate":"0.0001",
                 "nextFundingTime":1790294400000,"time":1790292781000}"""));
        exchange.on("/fapi/v1/fundingRate", Answer.ok("[]"));
        exchange.on("/futures/data/openInterestHist", Answer.ok("[]"));
        exchange.on("/futures/data/globalLongShortAccountRatio", Answer.ok("[]"));
        client = clientFor(exchange.baseUrl());
        data.pair("BTCUSDT", false, true, null, "TRADING", 0);
    }

    @AfterEach
    void stopEverything() {
        client.close();
        exchange.close();
        data.clear();
    }

    @Test
    void NSF04_aFinishedRun_isCompletedAndSchedulesNothing() {
        assertThat(job(true).run()).isEqualTo(Outcome.COMPLETED);
        assertThat(scheduled).isEmpty();
    }

    /** A rate limit: run again at the exchange's retryAt; a newer continuation replaces the pending one. */
    @Test
    void NSF04_aRateLimit_isRunAgainAtRetryAt() {
        exchange.on(FUNDING_INFO, Answer.status(429).withHeader("Retry-After", "30"));
        FuturesMetricsJob job = job(true);

        assertThat(job.run()).isEqualTo(Outcome.CONTINUATION_SCHEDULED);
        clock.set(NOW.plusSeconds(31));
        assertThat(job.run()).isEqualTo(Outcome.CONTINUATION_SCHEDULED);

        assertThat(scheduled).extracting(Scheduled::at).containsExactly(NOW.plusSeconds(30), NOW.plusSeconds(61));
        assertThat(cancelled).containsExactly(true);
    }

    /** An outage has no retry instant: nothing is scheduled beyond the next regular run. */
    @Test
    void NSF04_anOutage_waitsForTheNextRun() {
        exchange.on(FUNDING_INFO, Answer.status(503));

        assertThat(job(true).run()).isEqualTo(Outcome.WAIT_FOR_NEXT_RUN);
        assertThat(scheduled).isEmpty();
    }

    /** An unexpected failure waits for the next run rather than escaping into the scheduler. */
    @Test
    void NSF04_anUnexpectedFailure_waitsForTheNextRun() {
        FuturesMetricsJob broken = new FuturesMetricsJob(null, recordingScheduler(), properties(true));

        assertThat(broken.run()).isEqualTo(Outcome.WAIT_FOR_NEXT_RUN);
    }

    /** A run that finds the previous one still going does nothing. */
    @Test
    void NSF04_aRunWhileTheLastIsGoing_isSkipped() throws Exception {
        exchange.on(FUNDING_INFO, Answer.ok("[]").after(Duration.ofMillis(800)));
        FuturesMetricsJob job = job(true);
        CompletableFuture<Outcome> first = CompletableFuture.supplyAsync(job::run);
        while (exchange.hits(FUNDING_INFO) == 0) {
            Thread.onSpinWait();
        }

        Outcome second = job.run();

        assertThat(second).isEqualTo(Outcome.SKIPPED);
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(Outcome.COMPLETED);
    }

    /** Enabled: scheduled every five minutes on the configured cron; disabled: nothing at all. */
    @Test
    void NSF04_startSchedulesTheCronOnlyWhenEnabled() {
        job(false).start();
        assertThat(scheduled).isEmpty();

        job(true).start();

        assertThat(scheduled).singleElement().satisfies(task -> {
            assertThat(task.trigger()).isInstanceOf(CronTrigger.class);
            assertThat(((CronTrigger) task.trigger()).getExpression()).isEqualTo("30 1/5 * * * *");
        });
    }

    private FuturesMetricsJob job(boolean enabled) {
        FuturesMetricsProperties properties = properties(enabled);
        FuturesMetricsService service = new FuturesMetricsServiceImpl(
                client, pairs, metrics, new LatestMarketData(), properties, transactions, clock);
        return new FuturesMetricsJob(service, recordingScheduler(), properties);
    }

    private static FuturesMetricsProperties properties(boolean enabled) {
        return new FuturesMetricsProperties(
                enabled,
                "30 1/5 * * * *",
                ZoneId.of("UTC"),
                Duration.ofHours(1),
                500,
                50,
                Duration.ofDays(30),
                Duration.ofMinutes(2));
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

    private BinanceRestClient clientFor(URI base) {
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
                        new BinanceClientProperties.CircuitBreaker(50, Duration.ofSeconds(30))),
                clock,
                JsonMapper.builder().build(),
                new InMemoryBinanceBans());
    }
}
