package com.cryptopilot.watchlist.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.watchlist.AlertTestData;
import com.cryptopilot.watchlist.AlertTriggeredRecorder;
import com.cryptopilot.watchlist.model.PriceAlert;
import com.cryptopilot.watchlist.model.PriceAlertHit;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.service.AlertExpiryService;
import com.cryptopilot.watchlist.service.AlertService;
import com.cryptopilot.watchlist.service.AlertTriggerService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recording a trigger against the database: the conditional update is the arbiter under concurrency, a pause wins over
 * a stale evaluation, a rollback publishes nothing; the engine's load and the expiry sweep.
 *
 * <p>Nothing is rolled back by the framework, because the concurrency tests need committed transactions; every test
 * writes its own rows and {@link AlertTestData#clear()} deletes them.
 *
 * <p>Rule: NSF-06, BR-18, BR-19, BR-20; SRS 3.4.4; TECHNICAL_DESIGN 7.9; D-89.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, AlertTriggeredRecorder.class})
class AlertTriggerServiceImplTest {

    @Autowired
    private AlertTriggerService triggers;

    @Autowired
    private AlertExpiryService expiry;

    @Autowired
    private AlertService alertService;

    @Autowired
    private AlertTriggeredRecorder recorder;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private TransactionTemplate transactions;

    private AlertTestData data;
    private UUID trader;
    private UUID pair;
    private UUID row;

    @BeforeEach
    void insertRows() {
        data = new AlertTestData(sql);
        trader = data.trader();
        pair = data.pair("TT" + System.nanoTime() % 1_000_000_000L + "USDT");
        row = data.watch(trader, pair);
    }

    @AfterEach
    void removeRows() {
        data.clear();
    }

    @Test
    void NSF06_twoThreadsTriggeringOneAlert_recordOneTrigger_andPublishOneEvent() throws Exception {
        UUID alert = data.priceAlert(trader, row, "SPOT", "GREATER_THAN", "100", "EVERY_TIME", 5, "ACTIVE", null);
        PriceAlertHit hit = hit(loaded(alert), "101");
        CyclicBarrier start = new CyclicBarrier(2);

        List<Boolean> results = race(2, () -> {
            start.await();
            return triggers.tryTrigger(hit);
        });

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(data.triggerCount(alert)).isOne();
        assertThat(recorder.of(alert)).singleElement().satisfies(event -> {
            assertThat(event.triggerCount()).isOne();
            assertThat(event.observedValue()).isEqualByComparingTo("101");
            assertThat(event.symbol()).startsWith("TT");
            assertThat(event.market()).isEqualTo(MarketType.SPOT);
        });
    }

    @Test
    void BR19_onceIsTriggeredOnce_andEveryTimeWaitsItsCooldownInTheDatabase() {
        UUID once = data.priceAlert(trader, row, "SPOT", "GREATER_THAN", "100", "ONCE", null, "ACTIVE", null);
        UUID every = data.priceAlert(trader, row, "SPOT", "GREATER_THAN", "100", "EVERY_TIME", 5, "ACTIVE", null);

        assertThat(triggers.tryTrigger(hit(loaded(once), "101"))).isTrue();
        assertThat(data.status(once)).isEqualTo("TRIGGERED");

        Instant first = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        assertThat(triggers.tryTrigger(hitAt(loaded(every), "101", first))).isTrue();
        assertThat(triggers.tryTrigger(hitAt(
                        loaded(every), "101", first.plus(Duration.ofMinutes(5)).minusSeconds(1))))
                .as("a second before the cooldown")
                .isFalse();
        assertThat(triggers.tryTrigger(hitAt(loaded(every), "101", first.plus(Duration.ofMinutes(5)))))
                .as("exactly the cooldown")
                .isTrue();
        assertThat(data.triggerCount(every)).isEqualTo(2);
        assertThat(data.status(every)).isEqualTo("ACTIVE");
    }

    @Test
    void BR19_oncePerBar_isRecordedOncePerHourCandle() {
        UUID alert = data.priceAlert(trader, row, "SPOT", "GREATER_THAN", "100", "ONCE_PER_BAR", null, "ACTIVE", null);
        Instant bar = Instant.now().truncatedTo(ChronoUnit.HOURS);

        assertThat(triggers.tryTrigger(new PriceAlertHit(loaded(alert), BigDecimal.TEN, bar, Instant.now())))
                .isTrue();
        assertThat(triggers.tryTrigger(new PriceAlertHit(loaded(alert), BigDecimal.TEN, bar, Instant.now())))
                .isFalse();
        assertThat(triggers.tryTrigger(
                        new PriceAlertHit(loaded(alert), BigDecimal.TEN, bar.plus(Duration.ofHours(1)), Instant.now())))
                .isTrue();
    }

    @Test
    void NSF06_anAlertPausedAfterTheEngineReadIt_isNotTriggered() {
        UUID alert = data.priceAlert(trader, row, "SPOT", "GREATER_THAN", "100", "EVERY_TIME", 5, "ACTIVE", null);
        PriceAlertHit stale = hit(loaded(alert), "101");

        alertService.pause(trader, alert);

        assertThat(triggers.tryTrigger(stale)).isFalse();
        assertThat(data.status(alert)).isEqualTo("PAUSED");
        assertThat(data.triggerCount(alert)).isZero();
        assertThat(recorder.of(alert)).isEmpty();
    }

    /** A trigger and a pause at the same moment: whichever wins, the count, the status and the events agree. */
    @Test
    void NSF06_aTriggerRacingAPause_leavesOneConsistentOutcome() throws Exception {
        UUID alert = data.priceAlert(trader, row, "SPOT", "GREATER_THAN", "100", "EVERY_TIME", 5, "ACTIVE", null);
        PriceAlertHit hit = hit(loaded(alert), "101");
        CyclicBarrier start = new CyclicBarrier(2);

        List<Boolean> results = race(2, () -> {
            boolean triggering = start.await() == 0;
            if (triggering) {
                return triggers.tryTrigger(hit);
            }
            try {
                alertService.pause(trader, alert);
            } catch (RuntimeException optimisticLockLost) {
                // The pause read the row before the trigger's update and lost the version check: the trigger won.
            }
            return null;
        });

        boolean triggered = results.contains(true);
        assertThat(data.triggerCount(alert)).isEqualTo(triggered ? 1 : 0);
        assertThat(recorder.of(alert)).hasSize(triggered ? 1 : 0);
        if (!triggered) {
            assertThat(data.status(alert)).isEqualTo("PAUSED");
        }
    }

    @Test
    void D89_aTriggerWhoseTransactionRollsBack_isNotStored_andPublishesNothing() {
        UUID alert = data.priceAlert(trader, row, "SPOT", "GREATER_THAN", "100", "EVERY_TIME", 5, "ACTIVE", null);
        PriceAlertHit hit = hit(loaded(alert), "101");

        transactions.executeWithoutResult(status -> {
            assertThat(triggers.tryTrigger(hit)).isTrue();
            status.setRollbackOnly();
        });

        assertThat(data.triggerCount(alert)).isZero();
        assertThat(recorder.of(alert)).isEmpty();
    }

    @Test
    void NSF06_theLoad_holdsActivePriceAlertsOnTheirOwnMarket_andNothingElse() {
        UUID spot = data.priceAlert(trader, row, "SPOT", "CROSS_ABOVE", "100", "ONCE", null, "ACTIVE", null);
        UUID futures = data.priceAlert(trader, row, "FUTURES", "CROSS_BELOW", "90", "ONCE", null, "ACTIVE", null);
        UUID paused = data.priceAlert(trader, row, "SPOT", "CROSS_ABOVE", "100", "ONCE", null, "PAUSED", null);
        UUID indicator = data.indicatorAlert(trader, row);

        List<PriceAlert> mine = triggers.activePriceAlerts().stream()
                .filter(alert -> alert.userId().equals(trader))
                .toList();

        assertThat(mine).extracting(PriceAlert::alertId).containsExactlyInAnyOrder(spot, futures);
        assertThat(mine).allSatisfy(alert -> {
            assertThat(alert.pairId()).isEqualTo(pair);
            assertThat(alert.watchlistId()).isEqualTo(row);
        });
        assertThat(mine)
                .filteredOn(alert -> alert.alertId().equals(futures))
                .singleElement()
                .satisfies(alert -> {
                    assertThat(alert.market()).isEqualTo(MarketType.FUTURES);
                    assertThat(alert.condition()).isEqualTo(ConditionOperator.CROSS_BELOW);
                });
        assertThat(triggers.activePriceAlert(paused)).isEmpty();
        assertThat(triggers.activePriceAlert(indicator)).isEmpty();
        assertThat(triggers.activeAlertsNotEvaluated()).isPositive();
    }

    @Test
    void BR19_theSweep_expiresActiveAndPausedAlertsPastTheirExpiry_andLeavesTriggeredOnes() {
        Instant past = Instant.now().minus(Duration.ofMinutes(1));
        Instant future = Instant.now().plus(Duration.ofDays(1));
        UUID active = data.priceAlert(trader, row, "SPOT", "GREATER_THAN", "1", "ONCE", null, "ACTIVE", past);
        UUID paused = data.priceAlert(trader, row, "SPOT", "GREATER_THAN", "1", "ONCE", null, "PAUSED", past);
        UUID triggered = data.priceAlert(trader, row, "SPOT", "GREATER_THAN", "1", "ONCE", null, "TRIGGERED", past);
        UUID later = data.priceAlert(trader, row, "SPOT", "GREATER_THAN", "1", "ONCE", null, "ACTIVE", future);

        assertThat(expiry.expireDue()).isGreaterThanOrEqualTo(2);

        assertThat(data.status(active)).isEqualTo("EXPIRED");
        assertThat(data.status(paused)).isEqualTo("EXPIRED");
        assertThat(data.status(triggered)).isEqualTo("TRIGGERED");
        assertThat(data.status(later)).isEqualTo("ACTIVE");
    }

    private PriceAlert loaded(UUID alertId) {
        return triggers.activePriceAlert(alertId).orElseThrow();
    }

    private static PriceAlertHit hit(PriceAlert alert, String price) {
        return hitAt(alert, price, Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    private static PriceAlertHit hitAt(PriceAlert alert, String price, Instant at) {
        return new PriceAlertHit(alert, new BigDecimal(price), at.truncatedTo(ChronoUnit.HOURS), at);
    }

    private static <T> List<T> race(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(task));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
