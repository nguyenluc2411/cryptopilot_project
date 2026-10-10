package com.cryptopilot.watchlist.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.event.MarketStreamReconnected;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.watchlist.config.AlertEngineProperties;
import com.cryptopilot.watchlist.model.AlertsChanged;
import com.cryptopilot.watchlist.model.PriceAlert;
import com.cryptopilot.watchlist.model.PriceAlertHit;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import com.cryptopilot.watchlist.service.AlertTriggerService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.slf4j.LoggerFactory;

/**
 * The engine around the books: the stream thread never waits, a refused trigger or a committed change makes the engine
 * read the alert again, a stream reconnection forgets only a stale previous price, and a stop ends the partitions.
 *
 * <p>{@link AlertTriggerService} is a test double, the books run on a test clock, and the partitions run on real
 * threads. A test learns that an update has been evaluated from a <i>probe</i>: a level alert that fires on every
 * evaluation, so waiting for its n-th trigger with Mockito's {@code timeout()} replaces sleeping.
 *
 * <p>Rule: NSF-06, BR-19, BR-20; SRS 3.4.4; TECHNICAL_DESIGN 7.9; D-87, D-88, D-89.
 *
 * <p>Reference: Meszaros, G. (2007). <i>xUnit Test Patterns</i>. Addison-Wesley (Test Double; Test Spy for the
 * service, Humble Object for the threads around the logic).
 * Fowler, M. (2011). <i>Eradicating Non-Determinism in Tests</i>. martinfowler.com (wait on a signal, never on
 * a fixed sleep).
 */
class AlertEngineTest {

    private static final Instant T0 = Instant.parse("2026-10-05T08:10:00Z");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000c002");
    private static final long WAIT = Duration.ofSeconds(5).toMillis();

    private final MutableTestClock clock = new MutableTestClock(T0);
    private final AlertTriggerService triggers = mock(AlertTriggerService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AlertEngine.class);
    private final List<Thread> started = new CopyOnWriteArrayList<>();

    /** Fires on every evaluation, at most once a minute (its cooldown); every price step below is a minute. */
    private final PriceAlert probe =
            alert(UUID.randomUUID(), PAIR, ConditionOperator.GREATER_THAN, "0", TriggerMode.EVERY_TIME, 1);

    private int probed;
    private AlertEngine engine;

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
        }
        logger.detachAppender(logs);
    }

    // ------------------------------------------------------------------------------------------
    // Stream thread and refused triggers

    @Test
    void NSF06_aFullPartitionQueue_neverBlocksTheStream_andWarnsOncePerMinute() {
        when(triggers.activePriceAlerts()).thenReturn(List.of());
        // The partition's thread never drains its queue, so the second pair already finds it full.
        engine = new AlertEngine(triggers, properties(1), clock, idleThreads());
        engine.start();

        for (int i = 0; i < 5; i++) {
            engine.onMinuteKline(kline(UUID.randomUUID(), "100", T0.plusSeconds(i)));
        }

        assertThat(logs.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("queue is full"));
        verify(triggers, never()).tryTrigger(any());
    }

    @Test
    void NSF06_aTriggerTheDatabaseRefuses_readsTheAlertAgain_andTheBookFollowsTheStoredRule() {
        PriceAlert held = alert(UUID.randomUUID(), "100", 1);
        // Edited meanwhile: a higher target at the next version.
        PriceAlert edited = alert(held.alertId(), "200", 2);
        when(triggers.activePriceAlerts()).thenReturn(List.of(held));
        when(triggers.activePriceAlert(held.alertId())).thenReturn(Optional.of(edited));
        when(triggers.tryTrigger(any())).thenReturn(false, true);
        engine = new AlertEngine(
                triggers, properties(1000), clock, Thread.ofVirtual().factory());
        engine.start();

        engine.onMinuteKline(kline(held.pairId(), "101", T0));
        verify(triggers, timeout(WAIT)).activePriceAlert(held.alertId());

        clock.advance(Duration.ofSeconds(1));
        engine.onMinuteKline(kline(held.pairId(), "201", T0.plusSeconds(1)));

        verify(triggers, timeout(WAIT))
                .tryTrigger(argThat(hit -> hit.alert().version() == 2
                        && hit.alert().threshold().compareTo(new BigDecimal("200")) == 0
                        && hit.observedValue().compareTo(new BigDecimal("201")) == 0));
        assertThat(engine.holds(held.alertId())).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // Stream reconnection (D-87: a previous price older than the staleness is forgotten)

    @Test
    void D87_aCrossDuringAShortOutage_firesOnce_onTheFirstPriceAfterTheReconnect() {
        PriceAlert cross =
                alert(UUID.randomUUID(), PAIR, ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME, 1);
        startWith(cross);

        price("99");
        // Two minutes without prices, well inside the five minutes of staleness.
        clock.advance(Duration.ofMinutes(2));
        engine.onStreamReconnected(new MarketStreamReconnected(MarketType.SPOT));
        price("101");
        price("102");

        verify(triggers, times(1)).tryTrigger(argThat(hitOf(cross)));
    }

    @Test
    void D87_aCrossDuringAnOutageLongerThanTheStaleness_doesNotFire_butTheNextRealCrossDoes() {
        PriceAlert cross =
                alert(UUID.randomUUID(), PAIR, ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME, 1);
        startWith(cross);

        price("99");
        clock.advance(Duration.ofMinutes(10));
        engine.onStreamReconnected(new MarketStreamReconnected(MarketType.SPOT));
        // No previous price any more: 101 becomes the first one, so the cross cannot be told.
        price("101");
        verify(triggers, never()).tryTrigger(argThat(hitOf(cross)));

        price("99");
        price("101");
        verify(triggers, times(1)).tryTrigger(argThat(hitOf(cross)));
    }

    @Test
    void D87_aReconnectOfTheOtherMarket_keepsThePreviousPrice() {
        PriceAlert cross =
                alert(UUID.randomUUID(), PAIR, ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME, 1);
        startWith(cross);

        price("99");
        clock.advance(Duration.ofMinutes(10));
        engine.onStreamReconnected(new MarketStreamReconnected(MarketType.FUTURES));
        price("101");

        verify(triggers, times(1)).tryTrigger(argThat(hitOf(cross)));
    }

    @Test
    void BR19_aOnceCrossTriggeredBeforeTheOutage_doesNotFireAgainAfterTheReconnect() {
        PriceAlert once = alert(UUID.randomUUID(), PAIR, ConditionOperator.CROSS_ABOVE, "100", TriggerMode.ONCE, null);
        startWith(once);

        price("99");
        price("101");
        verify(triggers, times(1)).tryTrigger(argThat(hitOf(once)));

        clock.advance(Duration.ofMinutes(2));
        engine.onStreamReconnected(new MarketStreamReconnected(MarketType.SPOT));
        price("99");
        price("101");

        verify(triggers, times(1)).tryTrigger(argThat(hitOf(once)));
        assertThat(engine.holds(once.alertId())).isFalse();
    }

    @Test
    void BR19_aLevelTriggeredBeforeTheOutage_waitsItsCooldownAfterTheReconnect() {
        PriceAlert level =
                alert(UUID.randomUUID(), PAIR, ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME, 5);
        startWith(level);

        price("101");
        clock.advance(Duration.ofMinutes(1));
        engine.onStreamReconnected(new MarketStreamReconnected(MarketType.SPOT));
        // Two minutes after its trigger: inside its five-minute cooldown.
        price("101");

        verify(triggers, times(1)).tryTrigger(argThat(hitOf(level)));
    }

    @Test
    void NSF06_manyReconnects_startNoThread_andTheEngineKeepsEvaluating() {
        startWith();
        int threads = started.size();

        for (int i = 0; i < 100; i++) {
            engine.onStreamReconnected(new MarketStreamReconnected(i % 2 == 0 ? MarketType.SPOT : MarketType.FUTURES));
        }
        price("101");

        assertThat(started).hasSize(threads);
        assertThat(started).allSatisfy(thread -> assertThat(thread.isAlive()).isTrue());
    }

    // ------------------------------------------------------------------------------------------
    // Changes, refill and stop

    @Test
    void NSF06_aCommittedEdit_replacesTheRuleInTheBook() {
        PriceAlert held =
                alert(UUID.randomUUID(), PAIR, ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME, 1);
        PriceAlert edited = withThreshold(held, "200");
        startWith(held);
        when(triggers.activePriceAlert(held.alertId())).thenReturn(Optional.of(edited));

        engine.onAlertsChanged(AlertsChanged.of(held.alertId()));
        price("150");
        price("201");

        verify(triggers, times(1)).tryTrigger(argThat(hitOf(held)));
        verify(triggers)
                .tryTrigger(argThat(hit -> hitOf(held).matches(hit)
                        && hit.alert().version() == edited.version()
                        && hit.observedValue().compareTo(new BigDecimal("201")) == 0));
    }

    @Test
    void NSF06_anEditCommittedDuringTheStartLoad_winsOverTheOlderSnapshot() {
        PriceAlert loaded =
                alert(UUID.randomUUID(), PAIR, ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME, 1);
        PriceAlert edited = withThreshold(loaded, "200");
        when(triggers.activePriceAlert(loaded.alertId())).thenReturn(Optional.of(edited));
        when(triggers.tryTrigger(any())).thenReturn(true);
        engine = new AlertEngine(triggers, properties(1000), clock, recordingThreads());
        // The edit's refresh runs while the snapshot is read, so the snapshot is applied after it.
        when(triggers.activePriceAlerts()).thenAnswer(invocation -> {
            engine.onAlertsChanged(AlertsChanged.of(loaded.alertId()));
            return List.of(loaded, probe);
        });
        engine.start();

        price("150");
        price("201");

        verify(triggers).tryTrigger(argThat(hit -> hitOf(loaded).matches(hit)));
        verify(triggers)
                .tryTrigger(argThat(hit -> hitOf(loaded).matches(hit)
                        && hit.alert().version() == edited.version()
                        && hit.observedValue().compareTo(new BigDecimal("201")) == 0));
    }

    @Test
    void NSF06_aStaleReadOfAPausedAlert_doesNotRemoveTheResumedRuleReadAfterIt() {
        PriceAlert held =
                alert(UUID.randomUUID(), PAIR, ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME, 1);
        // Paused at the next version, resumed at the one after.
        PriceAlert resumed = alert(
                held.alertId(),
                PAIR,
                MarketType.SPOT,
                ConditionOperator.GREATER_THAN,
                "100",
                TriggerMode.EVERY_TIME,
                1,
                held.version() + 2);
        startWith(held);
        AtomicInteger reads = new AtomicInteger();
        // The first read sees the pause; before it is applied, the resume commits and its refresh completes.
        when(triggers.activePriceAlert(held.alertId())).thenAnswer(invocation -> {
            if (reads.incrementAndGet() == 1) {
                engine.onAlertsChanged(AlertsChanged.of(held.alertId()));
                return Optional.empty();
            }
            return Optional.of(resumed);
        });

        engine.onAlertsChanged(AlertsChanged.of(held.alertId()));
        price("101");

        assertThat(engine.holds(held.alertId())).isTrue();
        verify(triggers)
                .tryTrigger(
                        argThat(hit -> hitOf(held).matches(hit) && hit.alert().version() == resumed.version()));
    }

    @Test
    void D89_aStaleRuleLeftInAnotherBook_isDroppedAfterOneRefusal_andNotTriedAgain() {
        UUID id = UUID.randomUUID();
        PriceAlert spot1 = spotAt(id, 1);
        PriceAlert futures2 = onFutures(spot1);
        PriceAlert spot3 = spotAt(id, 3);
        startWith(spot1);
        when(triggers.tryTrigger(argThat(hitOf(spot1)))).thenReturn(false);
        AtomicInteger reads = new AtomicInteger();
        when(triggers.activePriceAlert(id)).thenAnswer(invocation -> switch (reads.incrementAndGet()) {
            case 1 -> {
                // The refresh of the move to FUTURES read v2; the move back to SPOT (v3) is applied before it.
                engine.onAlertsChanged(AlertsChanged.of(id));
                yield Optional.of(futures2);
            }
            case 2 -> Optional.of(spot3);
            default -> Optional.empty(); // paused at v4
        });
        engine.onAlertsChanged(AlertsChanged.of(id));
        engine.onAlertsChanged(AlertsChanged.of(id));
        assertThat(engine.holds(id)).as("located lost the SPOT rule").isFalse();

        price("101");
        verify(triggers, timeout(WAIT))
                .tryTrigger(
                        argThat(hit -> hitOf(spot1).matches(hit) && hit.alert().version() == 3));
        price("102");
        price("103");

        verify(triggers, times(1)).tryTrigger(argThat(hitOf(spot1)));
    }

    @Test
    void D89_aRefusalWhileTheRowStaysActiveAtTheSameVersion_keepsTheAlert_andItIsTriedAgain() {
        PriceAlert held = spotAt(UUID.randomUUID(), 1);
        startWith(held);
        when(triggers.tryTrigger(argThat(hitOf(held)))).thenReturn(false, true);
        when(triggers.activePriceAlert(held.alertId())).thenReturn(Optional.of(held));

        price("101");
        verify(triggers, timeout(WAIT)).activePriceAlert(held.alertId());
        price("102");

        assertThat(engine.holds(held.alertId())).isTrue();
        verify(triggers, timeout(WAIT).times(2)).tryTrigger(argThat(hitOf(held)));
    }

    @Test
    void NSF06_repeatedRefusals_warnFromTheThird_atMostOnceAMinutePerAlert() {
        PriceAlert held = spotAt(UUID.randomUUID(), 1);
        when(triggers.activePriceAlerts()).thenReturn(List.of(held));
        when(triggers.tryTrigger(any())).thenReturn(false);
        AtomicLong version = new AtomicLong(1);
        // Every read finds a newer ACTIVE row, so the rule comes back and is refused again.
        when(triggers.activePriceAlert(held.alertId()))
                .thenAnswer(invocation -> Optional.of(spotAt(held.alertId(), version.incrementAndGet())));
        engine = new AlertEngine(triggers, properties(1000), clock, recordingThreads());
        engine.start();

        for (int refusal = 1; refusal <= 5; refusal++) {
            refuseOnce(held, refusal, Duration.ofSeconds(1));
        }
        assertThat(refusalWarnings()).isEqualTo(1);

        refuseOnce(held, 6, Duration.ofMinutes(1));
        assertThat(refusalWarnings()).isEqualTo(2);
    }

    @Test
    void NSF06_aCommittedPauseOrDelete_takesTheAlertOutOfTheBook() {
        PriceAlert held =
                alert(UUID.randomUUID(), PAIR, ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME, 1);
        startWith(held);
        when(triggers.activePriceAlert(held.alertId())).thenReturn(Optional.empty());

        engine.onAlertsChanged(AlertsChanged.of(held.alertId()));
        price("101");

        assertThat(engine.holds(held.alertId())).isFalse();
        verify(triggers, never()).tryTrigger(argThat(hitOf(held)));
    }

    @Test
    void NSF06_anAlertMovedToAnotherMarket_isEvaluatedOnThatMarketOnly() {
        PriceAlert held =
                alert(UUID.randomUUID(), PAIR, ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME, 1);
        PriceAlert moved = onFutures(held);
        startWith(held);
        when(triggers.activePriceAlert(held.alertId())).thenReturn(Optional.of(moved));

        engine.onAlertsChanged(AlertsChanged.of(held.alertId()));
        price("101");
        verify(triggers, never()).tryTrigger(argThat(hitOf(held)));

        engine.onMinuteKline(new MinuteKline(
                MarketType.FUTURES, PAIR, T0, BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("101"), false, now()));
        verify(triggers, timeout(WAIT)).tryTrigger(argThat(hitOf(held)));
    }

    @Test
    void NSF06_pairsThatWaitedOutsideAFullQueue_areEvaluatedOnceThereIsRoom() throws Exception {
        UUID first = UUID.randomUUID();
        UUID queued = UUID.randomUUID();
        UUID waiting = UUID.randomUUID();
        List<PriceAlert> alerts = List.of(level(first), level(queued), level(waiting));
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(triggers.activePriceAlerts()).thenReturn(alerts);
        when(triggers.tryTrigger(any())).thenAnswer(call -> {
            if (call.<PriceAlertHit>getArgument(0).alert().pairId().equals(first)) {
                busy.countDown();
                release.await();
            }
            return true;
        });
        engine = new AlertEngine(triggers, properties(1), clock, recordingThreads());
        engine.start();

        engine.onMinuteKline(kline(first, "101", now()));
        busy.await();
        // The partition is busy with the first pair: the second fills the queue, the third waits outside it.
        engine.onMinuteKline(kline(queued, "101", now()));
        engine.onMinuteKline(kline(waiting, "101", now()));
        release.countDown();

        verify(triggers, timeout(WAIT))
                .tryTrigger(argThat(hit -> hit.alert().pairId().equals(queued)));
        verify(triggers, timeout(WAIT))
                .tryTrigger(argThat(hit -> hit.alert().pairId().equals(waiting)));
    }

    /**
     * A stop while a trigger is being recorded: the trigger finishes (its event is published with it), the pairs still
     * queued are dropped with the books, as at any shutdown (D-87: the books are loaded again at the next start, and a
     * cross needs a fresh previous price), and the thread ends.
     */
    @Test
    void NSF06_aStopDuringATrigger_returnsWithinItsTimeout_letsTheTriggerFinish_andEndsTheThread() throws Exception {
        UUID first = UUID.randomUUID();
        UUID queued = UUID.randomUUID();
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(triggers.activePriceAlerts()).thenReturn(List.of(level(first), level(queued)));
        when(triggers.tryTrigger(any())).thenAnswer(call -> {
            busy.countDown();
            // A database call that does not react to the interrupt.
            awaitUninterruptibly(release);
            finished.countDown();
            return true;
        });
        engine = new AlertEngine(triggers, properties(1000, Duration.ofMillis(200)), clock, recordingThreads());
        engine.start();
        engine.onMinuteKline(kline(first, "101", now()));
        busy.await();
        engine.onMinuteKline(kline(queued, "101", now()));

        long before = System.nanoTime();
        engine.stop();
        Duration took = Duration.ofNanos(System.nanoTime() - before);
        release.countDown();

        assertThat(took).isLessThan(Duration.ofSeconds(2));
        assertThat(engine.isRunning()).isFalse();
        assertThat(finished.await(WAIT, TimeUnit.MILLISECONDS)).isTrue();
        for (Thread thread : started) {
            thread.join(Duration.ofMillis(WAIT));
            assertThat(thread.isAlive()).isFalse();
        }
        verify(triggers, never()).tryTrigger(argThat(hit -> hit.alert().pairId().equals(queued)));
    }

    @Test
    void NSF06_aStoppedEngine_ignoresPrices_andAStartLoadsTheBooksAgain() {
        PriceAlert held =
                alert(UUID.randomUUID(), PAIR, ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME, 1);
        startWith(held);
        engine.stop();

        engine.onMinuteKline(kline(PAIR, "101", now()));
        assertThat(engine.holds(held.alertId())).isFalse();

        engine.start();
        assertThat(engine.holds(held.alertId())).isTrue();
        verify(triggers, times(2)).activePriceAlerts();
    }

    // ------------------------------------------------------------------------------------------
    // Helpers

    /** Starts one partition on recorded threads, holding the probe and these alerts; every trigger is accepted. */
    private void startWith(PriceAlert... alerts) {
        List<PriceAlert> held = new ArrayList<>(List.of(alerts));
        held.add(probe);
        when(triggers.activePriceAlerts()).thenReturn(held);
        when(triggers.tryTrigger(any())).thenReturn(true);
        engine = new AlertEngine(triggers, properties(1000), clock, recordingThreads());
        engine.start();
    }

    /** A minute later, a SPOT price for the pair; returns once the engine has evaluated it. */
    private void price(String close) {
        clock.advance(Duration.ofMinutes(1));
        engine.onMinuteKline(kline(PAIR, close, now()));
        verify(triggers, timeout(WAIT).times(++probed)).tryTrigger(argThat(hitOf(probe)));
    }

    /** Moves the clock, sends a price that meets the alert, and returns once the refusal has been handled. */
    private void refuseOnce(PriceAlert alert, int nth, Duration later) {
        clock.advance(later);
        engine.onMinuteKline(kline(alert.pairId(), "101", now()));
        verify(triggers, timeout(WAIT).times(nth)).activePriceAlert(alert.alertId());
    }

    private long refusalWarnings() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains("refused by the database"))
                .count();
    }

    private static PriceAlert spotAt(UUID alertId, long version) {
        return alert(
                alertId,
                PAIR,
                MarketType.SPOT,
                ConditionOperator.GREATER_THAN,
                "100",
                TriggerMode.EVERY_TIME,
                1,
                version);
    }

    private Instant now() {
        return clock.instant();
    }

    private ThreadFactory recordingThreads() {
        ThreadFactory virtual = Thread.ofVirtual().factory();
        return task -> {
            Thread thread = virtual.newThread(task);
            started.add(thread);
            return thread;
        };
    }

    /** Threads that run nothing: the partition's queue is never drained. */
    private static ThreadFactory idleThreads() {
        return ignored -> Thread.ofVirtual().unstarted(() -> {});
    }

    private static ArgumentMatcher<PriceAlertHit> hitOf(PriceAlert alert) {
        return hit -> hit != null && hit.alert().alertId().equals(alert.alertId());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static AlertEngineProperties properties(int queueCapacity) {
        return properties(queueCapacity, Duration.ofSeconds(5));
    }

    private static AlertEngineProperties properties(int queueCapacity, Duration stopTimeout) {
        return new AlertEngineProperties(
                true,
                1,
                queueCapacity,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                stopTimeout,
                new AlertEngineProperties.ExpirySweep(false, "0 * * * * *", "UTC"));
    }

    private static MinuteKline kline(UUID pairId, String close, Instant eventTime) {
        BigDecimal price = new BigDecimal(close);
        return new MinuteKline(MarketType.SPOT, pairId, T0, price, price, price, false, eventTime);
    }

    /** A GREATER_THAN 100 alert on its own pair. */
    private static PriceAlert level(UUID pairId) {
        return alert(UUID.randomUUID(), pairId, ConditionOperator.GREATER_THAN, "100", TriggerMode.EVERY_TIME, 1);
    }

    private static PriceAlert alert(UUID alertId, String threshold, long version) {
        return alert(
                alertId,
                PAIR,
                MarketType.SPOT,
                ConditionOperator.GREATER_THAN,
                threshold,
                TriggerMode.EVERY_TIME,
                5,
                version);
    }

    private static PriceAlert alert(
            UUID alertId,
            UUID pairId,
            ConditionOperator condition,
            String threshold,
            TriggerMode mode,
            Integer cooldown) {
        return alert(alertId, pairId, MarketType.SPOT, condition, threshold, mode, cooldown, 1);
    }

    private static PriceAlert withThreshold(PriceAlert alert, String threshold) {
        return alert(
                alert.alertId(),
                alert.pairId(),
                alert.market(),
                alert.condition(),
                threshold,
                alert.triggerMode(),
                alert.cooldownMinutes(),
                alert.version() + 1);
    }

    private static PriceAlert onFutures(PriceAlert alert) {
        return alert(
                alert.alertId(),
                alert.pairId(),
                MarketType.FUTURES,
                alert.condition(),
                alert.threshold().toPlainString(),
                alert.triggerMode(),
                alert.cooldownMinutes(),
                alert.version() + 1);
    }

    private static PriceAlert alert(
            UUID alertId,
            UUID pairId,
            MarketType market,
            ConditionOperator condition,
            String threshold,
            TriggerMode mode,
            Integer cooldown,
            long version) {
        return new PriceAlert(
                alertId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                pairId,
                "BTCUSDT",
                market,
                condition,
                new BigDecimal(threshold),
                mode,
                cooldown,
                null,
                true,
                false,
                false,
                0,
                null,
                null,
                version);
    }
}
