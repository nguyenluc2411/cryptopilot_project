package com.cryptopilot.watchlist.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.event.OpenInterestSampled;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.watchlist.AlertTestData;
import com.cryptopilot.watchlist.AlertTriggeredRecorder;
import com.cryptopilot.watchlist.event.AlertTriggered;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.service.OpenInterestAlertService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OPEN_INTEREST_CHANGE alerts on the database: a committed reading fires the alerts of its pair at once, a rolled-back
 * one fires nothing, the value comes from the stored readings alone, and the conditional update lets one reading
 * fire an alert at most once, also when two evaluations of it run at the same time.
 *
 * <p>Same context as {@link AlertEngineIntegrationTest}. Nothing is rolled back by the framework; the rows are deleted
 * after each test.
 *
 * <p>Rule: NSF-06, BR-19, BR-20; SRS 3.4.4; D-89; Q-39, Q-45.
 *
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 11 (idempotent
 * processing of a repeated event).
 */
@SpringBootTest(properties = "cryptopilot.alert.enabled=true")
@Import({TestcontainersConfig.class, AlertTriggeredRecorder.class, AlertEngineIntegrationTest.TestClock.class})
class OpenInterestAlertIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:06:40Z");
    private static final Instant T = Instant.parse("2026-10-14T10:05:00Z");
    private static final Duration STEP = Duration.ofMinutes(5);
    private static final int RACES = 20;

    @Autowired
    private OpenInterestAlertService openInterestAlerts;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private AlertTriggeredRecorder recorder;

    @Autowired
    private MutableTestClock clock;

    @Autowired
    private JdbcClient sql;

    private AlertTestData data;
    private String symbol;
    private UUID trader;
    private UUID pair;
    private UUID row;

    @BeforeEach
    void insertRows() {
        clock.set(NOW);
        data = new AlertTestData(sql);
        trader = data.trader();
        symbol = "OI" + System.nanoTime() % 1_000_000_000L + "USDT";
        pair = data.pair(symbol);
        row = data.watch(trader, pair);
    }

    @AfterEach
    void removeRows() {
        sql.sql("delete from futures_market_data where pair_id = ?").param(pair).update();
        data.clear();
    }

    @Test
    void NSF06_aCommittedReading_firesOnce_andIsPublishedBeforeTheCommitReturns() {
        UUID alert = openInterest("CROSS_ABOVE", "10", "ONCE", null, null);
        risingFrom5To12Percent(T);

        long start = System.nanoTime();
        transactions.executeWithoutResult(status -> events.publishEvent(sampled(T)));
        Duration latency = Duration.ofNanos(System.nanoTime() - start);

        assertThat(recorder.of(alert)).hasSize(1);
        assertThat(latency).isLessThan(Duration.ofSeconds(1));
        AlertTriggered event = recorder.of(alert).getFirst();
        assertThat(event.type()).isEqualTo(AlertType.INDICATOR);
        assertThat(event.indicator()).isEqualTo(AlertIndicator.OPEN_INTEREST_CHANGE);
        assertThat(event.observedValue()).isEqualByComparingTo("12");
        assertThat(event.threshold()).isEqualByComparingTo("10");
        assertThat(event.symbol()).isEqualTo(symbol);
        assertThat(data.triggerCount(alert)).isOne();
        assertThat(data.status(alert)).isEqualTo("TRIGGERED");
    }

    @Test
    void NSF06_aReadingWhoseTransactionRollsBack_firesNothing() {
        UUID alert = openInterest("CROSS_ABOVE", "10", "ONCE", null, null);
        risingFrom5To12Percent(T);

        transactions.executeWithoutResult(status -> {
            events.publishEvent(sampled(T));
            status.setRollbackOnly();
        });

        assertThat(data.triggerCount(alert)).isZero();
        assertThat(recorder.of(alert)).isEmpty();
    }

    @Test
    void Q39_anAlertExpiringNow_doesNotFire_oneExpiringLaterDoes() {
        UUID expired = openInterest("GREATER_THAN", "10", "ONCE", null, NOW);
        UUID later = openInterest("GREATER_THAN", "10", "ONCE", null, NOW.plusSeconds(1));
        risingFrom5To12Percent(T);

        assertThat(openInterestAlerts.evaluate(sampled(T))).isOne();

        assertThat(data.triggerCount(expired)).isZero();
        assertThat(data.triggerCount(later)).isOne();
    }

    @Test
    void Q45_theValue_comesFromTheStoredReadingsAlone_soARestartReadsTheSame() {
        UUID first = openInterest("CROSS_ABOVE", "10", "EVERY_TIME", 1, null);
        risingFrom5To12Percent(T);
        assertThat(openInterestAlerts.evaluate(sampled(T))).isOne();

        // An alert created afterwards, as after a restart: nothing but the rows decides its value.
        UUID second = openInterest("CROSS_ABOVE", "10", "EVERY_TIME", 1, null);
        assertThat(openInterestAlerts.evaluate(sampled(T))).isOne();

        assertThat(recorder.of(first).getFirst().observedValue())
                .isEqualByComparingTo(recorder.of(second).getFirst().observedValue());
        assertThat(data.triggerCount(first)).as("the same reading again").isOne();
    }

    @Test
    void D89_twoEvaluationsOfTheSameReadingAtOnce_recordOneTrigger() throws Exception {
        risingFrom5To12Percent(T);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int race = 0; race < RACES; race++) {
                UUID alert = openInterest("CROSS_ABOVE", "10", "EVERY_TIME", 1, null);
                CyclicBarrier start = new CyclicBarrier(2);
                List<Future<Integer>> both = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    both.add(pool.submit(() -> {
                        start.await();
                        return openInterestAlerts.evaluate(sampled(T));
                    }));
                }

                assertThat(both.get(0).get() + both.get(1).get())
                        .as("race %d", race)
                        .isOne();
                assertThat(data.triggerCount(alert)).as("race %d", race).isOne();
                assertThat(recorder.of(alert)).as("race %d", race).hasSize(1);
            }
        }
    }

    @Test
    void BR19_theSameReadingAgain_orAnOlderOne_firesNothing_aNewerOneFires() {
        UUID alert = openInterest("GREATER_THAN", "10", "EVERY_TIME", 1, null);
        twelvePercentAt(T.minus(STEP), T, T.plus(STEP));
        assertThat(openInterestAlerts.evaluate(sampled(T))).isOne();
        clock.advance(Duration.ofMinutes(2));

        assertThat(openInterestAlerts.evaluate(sampled(T))).isZero();
        assertThat(openInterestAlerts.evaluate(sampled(T.minus(STEP)))).isZero();
        assertThat(openInterestAlerts.evaluate(sampled(T.plus(STEP)))).isOne();

        assertThat(data.triggerCount(alert)).isEqualTo(2);
    }

    @Test
    void BR19_oncePerBar_firesOncePerUtcHourOfTheReadings() {
        UUID alert = openInterest("GREATER_THAN", "10", "ONCE_PER_BAR", null, null);
        twelvePercentAt(T, T.plus(STEP), Instant.parse("2026-10-14T11:00:00Z"));

        assertThat(openInterestAlerts.evaluate(sampled(T))).isOne();
        assertThat(openInterestAlerts.evaluate(sampled(T.plus(STEP))))
                .as("10:10, same hour")
                .isZero();
        assertThat(openInterestAlerts.evaluate(sampled(Instant.parse("2026-10-14T11:00:00Z"))))
                .isOne();

        assertThat(data.triggerCount(alert)).isEqualTo(2);
        assertThat(data.status(alert)).isEqualTo("ACTIVE");
    }

    /** 1000 from an hour before T − 5 min, 1050 at T − 5 min (5 %), 1120 at T (12 %). */
    private void risingFrom5To12Percent(Instant at) {
        reading(at.minus(Duration.ofMinutes(65)), "1000");
        reading(at.minus(Duration.ofMinutes(60)), "1000");
        reading(at.minus(STEP), "1050");
        reading(at, "1120");
    }

    /** 1000 every five minutes from T − 65 min to 11:00, except 1120 (12 % over the hour) at each given reading. */
    private void twelvePercentAt(Instant... samples) {
        Instant last = Instant.parse("2026-10-14T11:00:00Z");
        for (Instant at = T.minus(Duration.ofMinutes(65)); !at.isAfter(last); at = at.plus(STEP)) {
            reading(at, "1000");
        }
        for (Instant sample : samples) {
            reading(sample, "1120");
        }
    }

    private void reading(Instant at, String openInterest) {
        sql.sql("""
                        insert into futures_market_data (pair_id, snapshot_time, open_interest) values (?, ?, ?)
                        on conflict (pair_id, snapshot_time) do update set open_interest = excluded.open_interest""")
                .params(pair, Timestamp.from(at), new BigDecimal(openInterest))
                .update();
    }

    private OpenInterestSampled sampled(Instant at) {
        return new OpenInterestSampled(pair, symbol, at);
    }

    private UUID openInterest(
            String condition, String percent, String triggerMode, Integer cooldownMinutes, Instant expiresAt) {
        return data.indicatorAlert(
                trader,
                row,
                "FUTURES",
                "OPEN_INTEREST_CHANGE",
                "1h",
                condition,
                percent,
                triggerMode,
                cooldownMinutes,
                "ACTIVE",
                expiresAt);
    }
}
