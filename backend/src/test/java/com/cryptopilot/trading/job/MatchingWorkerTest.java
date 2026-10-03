package com.cryptopilot.trading.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.trading.config.MatchingProperties;
import com.cryptopilot.trading.event.TradingPlanActivated;
import com.cryptopilot.trading.event.TradingPlanCancelled;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.service.MatchingService;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The partitions and channels around the engine, with the storage side mocked. One partition makes the order of the
 * commands certain, so "a later command was handled" proves an earlier one was handled too.
 */
class MatchingWorkerTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000b001");
    private static final Instant AT = Instant.parse("2026-10-03T08:00:00Z");
    private static final long WAIT = Duration.ofSeconds(5).toMillis();
    /** Long before every range of these tests, so the activation guard (D-77) never holds an entry back. */
    private static final Instant ACTIVATED = AT.minusSeconds(3600);

    private static final UUID MARKER_PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000b0ff");
    /** No back-off: a failed fill is tried again on the pair's next range. */
    private static final MatchingProperties.Retry RETRY_AT_ONCE =
            new MatchingProperties.Retry(Duration.ZERO, Duration.ZERO, 0, Duration.ofMinutes(10));

    private static final MatchingProperties.Replay REPLAY =
            new MatchingProperties.Replay(false, Duration.ofHours(24), Duration.ZERO, 3, 1440);

    private final MatchingService matching = mock(MatchingService.class);
    private final MutableTestClock clock = new MutableTestClock(AT);
    private final List<Thread> started = new CopyOnWriteArrayList<>();
    private final ThreadFactory recording = task -> {
        Thread thread = Thread.ofVirtual().unstarted(task);
        started.add(thread);
        return thread;
    };
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger workerLogger = (Logger) LoggerFactory.getLogger(MatchingWorker.class);

    private MatchingWorker worker;

    @BeforeEach
    void captureLogs() {
        logs.start();
        workerLogger.addAppender(logs);
    }

    @AfterEach
    void stop() throws InterruptedException {
        workerLogger.detachAppender(logs);
        if (worker != null) {
            worker.stop();
        }
        for (Thread thread : started) {
            thread.join(WAIT);
        }
    }

    @Test
    void NSF07_whenDisabled_nothingStartsAndNothingIsQueued() {
        worker = new MatchingWorker(
                matching,
                new MatchingProperties(false, 1, 1, RETRY_AT_ONCE, 60, Duration.ofSeconds(5), REPLAY),
                clock,
                recording,
                () -> 0.0);

        worker.start();
        worker.onActivated(activated(UUID.randomUUID(), EntryType.LIMIT, "100"));
        worker.onActivated(activated(UUID.randomUUID(), EntryType.LIMIT, "100"));
        worker.onCancelled(cancelled(UUID.randomUUID()));
        worker.onCancelled(cancelled(UUID.randomUUID()));

        assertThat(worker.submit(range("90", "110", AT))).isFalse();
        assertThat(started).isEmpty();
        verifyNoInteractions(matching);
    }

    @Test
    void NSF07_start_loadsTheActiveEntriesAndStartsOneNamedConsumerPerPartition_once() {
        worker = new MatchingWorker(matching, properties(3, 10), clock, recording, () -> 0.0);

        worker.start();
        worker.start();

        verify(matching, times(1)).activeEntries();
        assertThat(started)
                .extracting(Thread::getName)
                .containsExactly("trading-matching-0", "trading-matching-1", "trading-matching-2");
    }

    @Test
    void NSF07_moreActiveEntriesOnOnePairThanTheChannelHolds_startAndStopWithoutHanging() {
        List<TrackedEntry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entries.add(entry(UUID.randomUUID(), Direction.LONG, "100"));
        }
        when(matching.activeEntries()).thenReturn(entries);
        worker = new MatchingWorker(matching, properties(1, 2), clock, recording, () -> 0.0);

        assertTimeoutPreemptively(Duration.ofSeconds(5), worker::start);
        worker.submit(range("99", "101", AT));

        verify(matching, timeout(WAIT).times(50)).fill(filledAt(AT));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            worker.stop();
            for (Thread thread : started) {
                thread.join();
            }
        });
    }

    @Test
    void NSF07_anEntryLoadedAtStart_isFilledWhenARangeReachesIt() {
        TrackedEntry entry = entry(UUID.randomUUID(), Direction.LONG, "100");
        when(matching.activeEntries()).thenReturn(List.of(entry));
        worker = started(2, 100);

        assertThat(worker.submit(range("99", "101", AT))).isTrue();

        verify(matching, timeout(WAIT)).fill(filled(entry.planId(), AT));
    }

    @Test
    void NSF07_aStartAfterAStop_rebuildsTheBooksFromScratch() throws InterruptedException {
        TrackedEntry gone = entry(UUID.randomUUID(), Direction.LONG, "100");
        TrackedEntry kept = entry(UUID.randomUUID(), Direction.LONG, "100");
        when(matching.activeEntries()).thenReturn(List.of(gone, kept)).thenReturn(List.of(kept));
        worker = started(1, 100);
        worker.stop();
        for (Thread thread : started) {
            thread.join(WAIT);
        }

        worker.start();
        worker.submit(range("99", "101", AT));

        verify(matching, timeout(WAIT)).fill(filled(kept.planId(), AT));
        verify(matching, never()).fill(filled(gone.planId(), AT));
    }

    @Test
    void NSF07_aLimitPlanActivated_isTracked_aMarketPlanIsNot() {
        UUID limit = UUID.randomUUID();
        UUID market = UUID.randomUUID();
        worker = started(1, 100);

        worker.onActivated(activated(market, EntryType.MARKET, "100"));
        worker.onActivated(activated(limit, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT));

        verify(matching, timeout(WAIT)).fill(filled(limit, AT));
        verify(matching, never()).fill(filled(market, AT));
    }

    @Test
    void NSF07_aCancelledPlan_leavesTheBooks() {
        UUID cancelled = UUID.randomUUID();
        UUID marker = UUID.randomUUID();
        worker = started(1, 100);
        worker.onActivated(activated(cancelled, EntryType.LIMIT, "100"));

        worker.onCancelled(cancelled(cancelled));
        worker.onActivated(activated(marker, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT));

        verify(matching, timeout(WAIT)).fill(filled(marker, AT));
        verify(matching, never()).fill(filled(cancelled, AT));
    }

    @Test
    void NSF07_aCancel_goesToItsPairsPartitionOnly_soFullPartitionsDoNotDelayIt() {
        int partitions = 4;
        UUID cancelledPair = pairIn(0, partitions);
        worker = new MatchingWorker(matching, properties(partitions, 1), clock, idle(), () -> 0.0);
        worker.start();
        for (int i = 1; i < partitions; i++) {
            worker.submit(new PriceRange(MarketType.SPOT, pairIn(i, partitions), BigDecimal.ONE, BigDecimal.TEN, AT));
        }

        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> worker.onCancelled(new TradingPlanCancelled(UUID.randomUUID(), MarketType.SPOT, cancelledPair)));
    }

    @Test
    void NSF07_anEntryThatLostTheRace_isNotTriedAgain() {
        UUID lost = UUID.randomUUID();
        UUID marker = UUID.randomUUID();
        when(matching.fill(filled(lost, AT))).thenReturn(false);
        worker = started(1, 100);
        worker.onActivated(activated(lost, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT));

        worker.onActivated(activated(marker, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT));

        verify(matching, timeout(WAIT)).fill(filled(marker, AT));
        verify(matching, times(1)).fill(filled(lost, AT));
    }

    @Test
    void NSF07_aFailedFill_isRetriedOnThePairsNextRange_atItsOriginalTime_evenIfThePriceMovedAway() {
        UUID plan = UUID.randomUUID();
        Instant later = AT.plusSeconds(60);
        when(matching.fill(filled(plan, AT)))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(true);
        worker = started(1, 100);
        worker.onActivated(activated(plan, EntryType.LIMIT, "100"));

        worker.submit(range("99", "101", AT));
        worker.submit(range("150", "160", later));

        verify(matching, timeout(WAIT).times(2)).fill(filled(plan, AT));
        verify(matching, never()).fill(filled(plan, later));
    }

    @Test
    void NSF07_aRangeOfAnotherPair_doesNotRetryAFailedFill() {
        UUID plan = UUID.randomUUID();
        UUID marker = UUID.randomUUID();
        UUID otherPair = UUID.randomUUID();
        when(matching.fill(filled(plan, AT))).thenThrow(new IllegalStateException("database down"));
        worker = started(1, 100);
        worker.onActivated(activated(plan, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT));

        worker.onActivated(new TradingPlanActivated(
                marker, MarketType.SPOT, otherPair, Direction.LONG, EntryType.LIMIT, new BigDecimal("100"), ACTIVATED));
        worker.submit(new PriceRange(MarketType.SPOT, otherPair, new BigDecimal("99"), new BigDecimal("101"), AT));

        verify(matching, timeout(WAIT)).fill(filled(marker, AT));
        verify(matching, times(1)).fill(filled(plan, AT));
    }

    @Test
    void NSF07_aCancelledPlan_leavesTheRetryList() {
        UUID plan = UUID.randomUUID();
        UUID marker = UUID.randomUUID();
        when(matching.fill(filled(plan, AT))).thenThrow(new IllegalStateException("database down"));
        worker = started(1, 100);
        worker.onActivated(activated(plan, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT));

        worker.onCancelled(cancelled(plan));
        worker.onActivated(activated(marker, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT));

        verify(matching, timeout(WAIT)).fill(filled(marker, AT));
        verify(matching, times(1)).fill(filled(plan, AT));
    }

    /**
     * The consumer is held inside a fill while the channel (capacity 2) fills up; the later updates of the same candle
     * wait merged into one pending range. Each update alone would reach one of the entries; the merge reaches them
     * all, and each fill carries the candle's open time (D-78).
     */
    @Test
    void NSF07_updatesOfOneCandleForAFullChannel_areMerged_andStillReachEveryLevel() throws InterruptedException {
        TrackedEntry first = entry(UUID.randomUUID(), Direction.LONG, "100");
        TrackedEntry second = entry(UUID.randomUUID(), Direction.LONG, "98");
        TrackedEntry byLow4 = entry(UUID.randomUUID(), Direction.LONG, "95");
        TrackedEntry byLow5 = entry(UUID.randomUUID(), Direction.LONG, "90");
        TrackedEntry byHigh4 = entry(UUID.randomUUID(), Direction.SHORT, "110");
        when(matching.activeEntries()).thenReturn(List.of(first, second, byLow4, byLow5, byHigh4));
        Gate inFirst = new Gate();
        Gate inSecond = new Gate();
        doAnswer(inFirst::pass).when(matching).fill(filledPlan(first.planId()));
        doAnswer(inSecond::pass).when(matching).fill(filledPlan(second.planId()));
        worker = started(1, 2);

        worker.submit(update("99", "101", AT, 0));
        inFirst.awaitEntered();
        worker.submit(update("97.5", "99", AT, 1));
        worker.submit(update("99.5", "100.5", AT, 2));
        worker.submit(update("94", "111", AT, 4));
        inFirst.release();
        inSecond.awaitEntered();
        worker.submit(update("89", "100", AT, 5));
        inSecond.release();

        verify(matching, timeout(WAIT)).fill(filled(byLow4.planId(), AT));
        verify(matching, timeout(WAIT)).fill(filled(byLow5.planId(), AT));
        verify(matching, timeout(WAIT)).fill(filled(byHigh4.planId(), AT));
        verify(matching, times(1)).fill(filledPlan(byLow4.planId()));
    }

    /**
     * N1: pending updates of two candles are not merged across the minute, so each entry is filled at the open time of
     * the candle that reached it, as a replay of the closed candles would fill it.
     */
    @Test
    void D78_pendingUpdatesOfTwoCandles_stayApart_andEachFillKeepsItsCandlesOpenTime() throws InterruptedException {
        Instant nextMinute = AT.plusSeconds(60);
        TrackedEntry holder = entry(UUID.randomUUID(), Direction.LONG, "100");
        TrackedEntry byFirst = entry(UUID.randomUUID(), Direction.LONG, "95");
        TrackedEntry bySecond = entry(UUID.randomUUID(), Direction.LONG, "90");
        when(matching.activeEntries()).thenReturn(List.of(holder, byFirst, bySecond));
        Gate held = new Gate();
        doAnswer(held::pass).when(matching).fill(filledPlan(holder.planId()));
        worker = started(1, 2);
        worker.submit(update("99", "101", AT, 0));
        held.awaitEntered();
        worker.submit(update("99", "101", AT, 10));
        worker.submit(update("99", "101", AT, 20));

        worker.submit(update("94", "101", AT, 30));
        worker.submit(update("94", "101", AT, 59));
        worker.submit(update("89", "92", nextMinute, 1));
        held.release();

        verify(matching, timeout(WAIT)).fill(filled(byFirst.planId(), AT));
        verify(matching, timeout(WAIT)).fill(filled(bySecond.planId(), nextMinute));
    }

    /**
     * N1: a pair's pending range is matched right after the command in progress, while the channel still holds another
     * pair's traffic, and not only once the channel is empty.
     */
    @Test
    void NSF07_aPendingRange_doesNotWaitBehindAnotherPairsTraffic() throws InterruptedException {
        UUID otherPair = UUID.randomUUID();
        TrackedEntry holder = new TrackedEntry(
                UUID.randomUUID(), MarketType.SPOT, otherPair, Direction.LONG, new BigDecimal("100"), ACTIVATED);
        TrackedEntry later = new TrackedEntry(
                UUID.randomUUID(), MarketType.SPOT, otherPair, Direction.SHORT, new BigDecimal("200"), ACTIVATED);
        TrackedEntry pending = entry(UUID.randomUUID(), Direction.LONG, "50");
        when(matching.activeEntries()).thenReturn(List.of(holder, later, pending));
        List<UUID> order = new CopyOnWriteArrayList<>();
        Gate held = new Gate();
        doAnswer(call -> {
                    order.add(holder.planId());
                    return held.pass(call);
                })
                .when(matching)
                .fill(filledPlan(holder.planId()));
        doAnswer(call -> order.add(((Fill) call.getArgument(0)).planId()))
                .when(matching)
                .fill(filledPlan(later.planId()));
        doAnswer(call -> order.add(((Fill) call.getArgument(0)).planId()))
                .when(matching)
                .fill(filledPlan(pending.planId()));
        worker = started(1, 2);
        worker.submit(new PriceRange(MarketType.SPOT, otherPair, new BigDecimal("99"), new BigDecimal("101"), AT));
        held.awaitEntered();
        worker.submit(new PriceRange(MarketType.SPOT, otherPair, new BigDecimal("150"), new BigDecimal("160"), AT));
        worker.submit(new PriceRange(MarketType.SPOT, otherPair, new BigDecimal("195"), new BigDecimal("205"), AT));

        worker.submit(range("49", "51", AT));
        held.release();

        verify(matching, timeout(WAIT)).fill(filledPlan(later.planId()));
        verify(matching, timeout(WAIT)).fill(filledPlan(pending.planId()));
        assertThat(order).containsExactly(holder.planId(), pending.planId(), later.planId());
    }

    /**
     * N1: however many updates of a pair wait as pending ranges, they put at most one wake-up in the channel, so the
     * room left stays for other commands: here an activation finds a free slot instead of waiting.
     */
    @Test
    void NSF07_manyPendingUpdatesOfAPair_putAtMostOneWakeUpInTheChannel() throws InterruptedException {
        UUID otherPair = UUID.randomUUID();
        TrackedEntry first = entry(UUID.randomUUID(), Direction.LONG, "100");
        TrackedEntry second = entry(UUID.randomUUID(), Direction.LONG, "95");
        TrackedEntry reached = entry(UUID.randomUUID(), Direction.LONG, "90");
        when(matching.activeEntries()).thenReturn(List.of(first, second, reached));
        Gate inFirst = new Gate();
        Gate inSecond = new Gate();
        doAnswer(inFirst::pass).when(matching).fill(filledPlan(first.planId()));
        doAnswer(inSecond::pass).when(matching).fill(filledPlan(second.planId()));
        worker = started(1, 4);
        worker.submit(update("99", "101", AT, 0));
        inFirst.awaitEntered();
        worker.submit(update("99", "101", AT, 1));
        worker.submit(update("94", "101", AT, 2));
        worker.submit(update("99", "101", AT, 3));
        worker.submit(update("99", "101", AT, 4));
        worker.submit(update("99", "101", AT, 5));
        inFirst.release();
        inSecond.awaitEntered();

        // Two slots are free; the 44 pending updates take one of them between them.
        for (int i = 6; i < 50; i++) {
            worker.submit(update("99", "101", AT, i));
        }
        UUID otherPlan = UUID.randomUUID();
        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> worker.onActivated(new TradingPlanActivated(
                        otherPlan,
                        MarketType.SPOT,
                        otherPair,
                        Direction.LONG,
                        EntryType.LIMIT,
                        new BigDecimal("10"),
                        ACTIVATED)));
        worker.submit(update("89", "101", AT, 50));
        inSecond.release();
        worker.submit(new PriceRange(MarketType.SPOT, otherPair, new BigDecimal("9"), new BigDecimal("11"), AT));

        verify(matching, timeout(WAIT)).fill(filled(reached.planId(), AT));
        verify(matching, timeout(WAIT)).fill(filled(otherPlan, AT));
    }

    @Test
    void NSF07_merge_keepsTheLowestLowTheHighestHighAndTheEarliestAndLatestTime_inEitherOrder() {
        PriceRange earlier = range("95", "105", AT);
        PriceRange later = range("97", "110", AT.plusSeconds(1));

        PriceRange expected = new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal("95"), new BigDecimal("110"), AT, AT.plusSeconds(1));
        assertThat(MatchingWorker.merge(earlier, later)).isEqualTo(expected);
        assertThat(MatchingWorker.merge(later, earlier)).isEqualTo(expected);
    }

    /**
     * A range kept pending because the channel was full, then an activation of the same pair: the range is matched
     * before the plan is tracked, so it cannot fill the new plan, and a later range does not merge across the
     * activation.
     */
    @Test
    void NSF07_aPendingRangeThatArrivedBeforeAnActivation_doesNotFillTheNewPlan() throws InterruptedException {
        TrackedEntry holder = entry(UUID.randomUUID(), Direction.LONG, "100");
        TrackedEntry secondHolder = entry(UUID.randomUUID(), Direction.SHORT, "155");
        TrackedEntry marker = entry(UUID.randomUUID(), Direction.SHORT, "165");
        when(matching.activeEntries()).thenReturn(List.of(holder, secondHolder, marker));
        Gate held = new Gate();
        Gate heldAgain = new Gate();
        doAnswer(held::pass).when(matching).fill(filledPlan(holder.planId()));
        doAnswer(heldAgain::pass).when(matching).fill(filledPlan(secondHolder.planId()));
        UUID activated = UUID.randomUUID();
        worker = started(1, 2);
        worker.submit(range("99", "101", AT));
        held.awaitEntered();
        worker.submit(range("150", "160", AT.plusSeconds(1)));
        worker.submit(range("150", "160", AT.plusSeconds(2)));
        worker.submit(range("80", "85", AT.plusSeconds(3)));

        Thread activation = waitingIn(() -> worker.onActivated(activated(activated, EntryType.LIMIT, "82")));
        worker.submit(range("150", "160", AT.plusSeconds(4)));
        // The consumer takes the next range and is held again, so the activation is in the channel before it goes on.
        held.release();
        heldAgain.awaitEntered();
        activation.join(WAIT);
        assertThat(activation.isAlive()).as("the activation is in the channel").isFalse();
        heldAgain.release();
        worker.submit(range("150", "170", AT.plusSeconds(5)));

        // The marker is reached only by the last range, so every earlier range has been matched once it is filled.
        verify(matching, timeout(WAIT)).fill(filledPlan(marker.planId()));
        verify(matching, never()).fill(filledPlan(activated));
        worker.submit(range("80", "85", AT.plusSeconds(6)));
        verify(matching, timeout(WAIT)).fill(filled(activated, AT.plusSeconds(6)));
    }

    /** An activation already in the channel: the pair's next range follows it there and fills the new plan. */
    @Test
    void NSF07_aRangeAfterAQueuedActivation_followsItAndFillsTheNewPlan() throws InterruptedException {
        TrackedEntry holder = entry(UUID.randomUUID(), Direction.LONG, "100");
        when(matching.activeEntries()).thenReturn(List.of(holder));
        Gate held = new Gate();
        doAnswer(held::pass).when(matching).fill(filledPlan(holder.planId()));
        UUID activated = UUID.randomUUID();
        worker = started(1, 10);
        worker.submit(range("99", "101", AT));
        held.awaitEntered();

        worker.onActivated(activated(activated, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT.plusSeconds(1)));
        held.release();

        verify(matching, timeout(WAIT)).fill(filled(activated, AT.plusSeconds(1)));
    }

    /** A range that arrives while an activation waits for room in the channel is matched after it, and fills it. */
    @Test
    void NSF07_aRangeThatArrivesWhileAnActivationWaitsForRoom_isMatchedAfterIt() throws InterruptedException {
        TrackedEntry holder = entry(UUID.randomUUID(), Direction.LONG, "100");
        when(matching.activeEntries()).thenReturn(List.of(holder));
        Gate held = new Gate();
        doAnswer(held::pass).when(matching).fill(filledPlan(holder.planId()));
        UUID activated = UUID.randomUUID();
        worker = started(1, 2);
        worker.submit(range("99", "101", AT));
        held.awaitEntered();
        worker.submit(range("150", "160", AT.plusSeconds(1)));
        worker.submit(range("150", "160", AT.plusSeconds(2)));

        Thread activation = waitingIn(() -> worker.onActivated(activated(activated, EntryType.LIMIT, "82")));
        worker.submit(range("80", "85", AT.plusSeconds(3)));
        held.release();
        activation.join(WAIT);

        verify(matching, timeout(WAIT)).fill(filled(activated, AT.plusSeconds(3)));
    }

    /** A range that reached an entry, kept pending, then a cancel of that plan: the earlier range fills it first. */
    @Test
    void NSF07_aPendingRangeThatReachedAnEntryBeforeItsCancel_stillFillsIt() throws InterruptedException {
        TrackedEntry holder = entry(UUID.randomUUID(), Direction.LONG, "100");
        TrackedEntry cancelled = entry(UUID.randomUUID(), Direction.LONG, "98");
        when(matching.activeEntries()).thenReturn(List.of(holder, cancelled));
        Gate held = new Gate();
        doAnswer(held::pass).when(matching).fill(filledPlan(holder.planId()));
        worker = started(1, 2);
        worker.submit(range("99", "101", AT));
        held.awaitEntered();
        worker.submit(range("150", "160", AT.plusSeconds(1)));
        worker.submit(range("150", "160", AT.plusSeconds(2)));
        worker.submit(range("97", "99", AT.plusSeconds(3)));

        Thread cancel = waitingIn(() -> worker.onCancelled(cancelled(cancelled.planId())));
        held.release();
        cancel.join(WAIT);

        verify(matching, timeout(WAIT)).fill(filled(cancelled.planId(), AT.plusSeconds(3)));
    }

    @Test
    void NSF07_aCheckedFailureOfOneFill_isRetried_andTheOtherEntriesOfTheRangeAreStillFilled() throws Exception {
        UUID failing = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        doAnswer(call -> {
                    throw new IOException("connection reset");
                })
                .doReturn(true)
                .when(matching)
                .fill(filled(failing, AT));
        worker = started(1, 100);
        worker.onActivated(activated(failing, EntryType.LIMIT, "100"));
        worker.onActivated(activated(other, EntryType.LIMIT, "100"));

        worker.submit(range("99", "101", AT));
        worker.submit(range("150", "160", AT.plusSeconds(60)));

        verify(matching, timeout(WAIT).times(2)).fill(filled(failing, AT));
        verify(matching, timeout(WAIT)).fill(filled(other, AT));
        assertThat(logs.list)
                .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("carries on"));
    }

    @Test
    void NSF07_aFailedFill_waitsForItsBackOff_thenIsTriedAgainOnThePairsNextRange_atItsOriginalTime() {
        UUID plan = UUID.randomUUID();
        when(matching.fill(filled(plan, AT)))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(true);
        worker = new MatchingWorker(matching, retrying(Duration.ofMinutes(10)), clock, recording, () -> 0.0);
        worker.start();
        worker.onActivated(activated(plan, EntryType.LIMIT, "100"));

        worker.submit(range("99", "101", AT));
        awaitProcessed();
        clock.advance(Duration.ofMillis(999));
        worker.submit(range("150", "160", AT.plusSeconds(60)));
        awaitProcessed();
        verify(matching, times(1)).fill(filledPlan(plan));

        clock.advance(Duration.ofMillis(1));
        worker.submit(range("150", "160", AT.plusSeconds(120)));
        awaitProcessed();

        verify(matching, times(2)).fill(filled(plan, AT));
    }

    @Test
    void NSF07_aFillStillFailingAtItsDeadline_isGivenUpWithOneErrorLog_andNothingElseTouchesThePlan() {
        UUID plan = UUID.randomUUID();
        when(matching.fill(filledPlan(plan))).thenThrow(new IllegalStateException("database down"));
        worker = new MatchingWorker(matching, retrying(Duration.ofSeconds(5)), clock, recording, () -> 0.0);
        worker.start();
        worker.onActivated(activated(plan, EntryType.LIMIT, "100"));

        worker.submit(range("99", "101", AT));
        awaitProcessed();
        clock.advance(Duration.ofSeconds(1));
        worker.submit(range("150", "160", AT.plusSeconds(60)));
        awaitProcessed();
        clock.advance(Duration.ofSeconds(4));
        worker.submit(range("150", "160", AT.plusSeconds(120)));
        awaitProcessed();
        clock.advance(Duration.ofMinutes(5));
        worker.submit(range("99", "101", AT.plusSeconds(180)));
        awaitProcessed();

        verify(matching, times(3)).fill(filled(plan, AT));
        verify(matching, never()).fill(filled(plan, AT.plusSeconds(180)));
        assertThat(logs.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains(plan.toString())
                        .contains("stays ACTIVE"));
        assertThat(logs.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .hasSize(2);
    }

    @Test
    void NSF07_theBackOff_doublesFromTheInitialDelay_upToTheMaximum_lessItsJitter() {
        MatchingProperties.Retry retry =
                new MatchingProperties.Retry(Duration.ofSeconds(1), Duration.ofSeconds(30), 20, Duration.ofMinutes(10));

        assertThat(MatchingWorker.backoff(retry, 1, 0.0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(MatchingWorker.backoff(retry, 2, 0.0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(MatchingWorker.backoff(retry, 3, 0.0)).isEqualTo(Duration.ofSeconds(4));
        assertThat(MatchingWorker.backoff(retry, 10, 0.0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(MatchingWorker.backoff(retry, 99, 0.0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(MatchingWorker.backoff(retry, 10, 0.5)).isEqualTo(Duration.ofSeconds(27));
        assertThat(MatchingWorker.backoff(retry, 1, 0.999)).isGreaterThan(Duration.ofMillis(800));
    }

    @Test
    void NSF07_aPairWhosePendingRangesPassTheThreshold_isWarnedAboutOnce() throws InterruptedException {
        TrackedEntry holder = entry(UUID.randomUUID(), Direction.LONG, "100");
        when(matching.activeEntries()).thenReturn(List.of(holder));
        Gate held = new Gate();
        doAnswer(held::pass).when(matching).fill(filledPlan(holder.planId()));
        worker = new MatchingWorker(
                matching,
                new MatchingProperties(true, 1, 1, RETRY_AT_ONCE, 3, Duration.ofSeconds(5), REPLAY),
                clock,
                recording,
                () -> 0.0);
        worker.start();
        worker.submit(range("99", "101", AT));
        held.awaitEntered();

        for (int minute = 1; minute <= 8; minute++) {
            worker.submit(range("150", "160", AT.plusSeconds(60L * minute)));
        }
        held.release();

        assertThat(logs.list)
                .filteredOn(event -> event.getFormattedMessage().contains("pending ranges"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("holds 4 pending ranges");
                });
    }

    @Test
    void NSF07_stopThenStart_repeatedly_neverRunsTwoGenerationsOfConsumersTogether() {
        AtomicInteger alive = new AtomicInteger();
        AtomicInteger mostAlive = new AtomicInteger();
        ThreadFactory counting = task -> recording.newThread(() -> {
            mostAlive.accumulateAndGet(alive.incrementAndGet(), Math::max);
            try {
                task.run();
            } finally {
                alive.decrementAndGet();
            }
        });
        TrackedEntry entry = entry(UUID.randomUUID(), Direction.LONG, "100");
        when(matching.activeEntries()).thenReturn(List.of(entry));
        doAnswer(call -> {
                    Thread.sleep(5);
                    return true;
                })
                .when(matching)
                .fill(any());
        worker = new MatchingWorker(matching, properties(2, 100), clock, counting, () -> 0.0);

        for (int i = 0; i < 20; i++) {
            worker.start();
            worker.submit(range("99", "101", AT));
            worker.stop();
            assertThat(alive.get())
                    .as("every consumer has ended when stop returns")
                    .isZero();
        }

        assertThat(mostAlive.get()).isLessThanOrEqualTo(2);
        assertThat(started).hasSize(40).noneMatch(Thread::isAlive);
    }

    @Test
    void NSF07_aConsumerThatOutlivesTheStopTimeout_blocksTheNextStart_untilItEnds() throws InterruptedException {
        TrackedEntry entry = entry(UUID.randomUUID(), Direction.LONG, "100");
        when(matching.activeEntries()).thenReturn(List.of(entry));
        CountDownLatch inFill = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        doAnswer(call -> {
                    inFill.countDown();
                    // Deaf to the interrupt, like a call that never checks it.
                    while (true) {
                        try {
                            letGo.await();
                            return true;
                        } catch (InterruptedException ignored) {
                            // keeps waiting
                        }
                    }
                })
                .when(matching)
                .fill(any());
        worker = new MatchingWorker(
                matching,
                new MatchingProperties(true, 1, 100, RETRY_AT_ONCE, 60, Duration.ofMillis(200), REPLAY),
                clock,
                recording,
                () -> 0.0);
        worker.start();
        worker.submit(range("99", "101", AT));
        assertThat(inFill.await(WAIT, TimeUnit.MILLISECONDS)).isTrue();

        worker.stop();
        worker.start();

        assertThat(started).hasSize(1);
        assertThat(worker.isRunning()).isFalse();
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("not started");
        });

        letGo.countDown();
        started.getFirst().join(WAIT);
        worker.start();

        assertThat(started).hasSize(2);
        assertThat(worker.isRunning()).isTrue();
    }

    @Test
    void A04_aClosedCandleFullyMatched_advancesThePairsWatermarkToItsOpenTime_aFormingOneDoesNot() {
        UUID plan = UUID.randomUUID();
        worker = started(1, 100);
        worker.onActivated(activated(plan, EntryType.LIMIT, "100"));

        worker.submit(closed("150", "160", AT));
        worker.submit(range("99", "101", AT.plusSeconds(60)));
        worker.submit(closed("99", "101", AT.plusSeconds(60)));

        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, AT.plusSeconds(60));
        verify(matching).advanceWatermark(MarketType.SPOT, PAIR, AT);
        verify(matching, times(2)).advanceWatermark(any(), any(), any());
    }

    @Test
    void A04_aClosedCandleWhosePairWaitsToRetryAFill_doesNotAdvanceTheWatermark() {
        UUID plan = UUID.randomUUID();
        when(matching.fill(filled(plan, AT)))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(true);
        worker = started(1, 100);
        worker.onActivated(activated(plan, EntryType.LIMIT, "100"));

        worker.submit(closed("99", "101", AT));
        worker.submit(closed("150", "160", AT.plusSeconds(60)));

        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.SPOT, PAIR, AT.plusSeconds(60));
        verify(matching, never()).advanceWatermark(MarketType.SPOT, PAIR, AT);
    }

    /** R2: a fill given up holds the pair's watermark before the candle that reached it, so a restart replays it. */
    @Test
    void R2_aFillGivenUp_holdsThePairsWatermarkBeforeTheCandleThatReachedIt() {
        UUID plan = UUID.randomUUID();
        when(matching.fill(filledPlan(plan))).thenThrow(new IllegalStateException("database down"));
        worker = new MatchingWorker(matching, retrying(Duration.ZERO), clock, recording, () -> 0.0);
        worker.start();
        worker.onActivated(activated(plan, EntryType.LIMIT, "100"));

        worker.submit(closed("150", "160", AT));
        worker.submit(closed("99", "101", AT.plusSeconds(60)));
        worker.submit(closed("150", "160", AT.plusSeconds(120)));
        awaitProcessed();

        verify(matching).advanceWatermark(MarketType.SPOT, PAIR, AT);
        verify(matching, never()).advanceWatermark(MarketType.SPOT, PAIR, AT.plusSeconds(60));
        verify(matching, never()).advanceWatermark(MarketType.SPOT, PAIR, AT.plusSeconds(120));
        assertThat(logs.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("watermark is held"));
    }

    @Test
    void A04_aWatermarkThatCannotBeWritten_isLogged_andMatchingCarriesOn() {
        UUID plan = UUID.randomUUID();
        doThrow(new IllegalStateException("database down")).when(matching).advanceWatermark(MarketType.SPOT, PAIR, AT);
        worker = started(1, 100);
        worker.submit(closed("150", "160", AT));

        worker.onActivated(activated(plan, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT.plusSeconds(60)));

        verify(matching, timeout(WAIT)).fill(filled(plan, AT.plusSeconds(60)));
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("watermark");
        });
    }

    @Test
    void NSF07_aCommandLostToAnInterrupt_doesNotHoldBackItsPairsRanges() throws InterruptedException {
        TrackedEntry holder = entry(UUID.randomUUID(), Direction.LONG, "100");
        TrackedEntry waiting = entry(UUID.randomUUID(), Direction.LONG, "98");
        when(matching.activeEntries()).thenReturn(List.of(holder, waiting));
        Gate held = new Gate();
        doAnswer(held::pass).when(matching).fill(filledPlan(holder.planId()));
        worker = started(1, 2);
        worker.submit(range("99", "101", AT));
        held.awaitEntered();
        worker.submit(range("150", "160", AT.plusSeconds(1)));
        worker.submit(range("150", "160", AT.plusSeconds(2)));

        Thread.currentThread().interrupt();
        worker.onCancelled(cancelled(UUID.randomUUID()));
        assertThat(Thread.interrupted()).isTrue();
        held.release();
        worker.submit(range("97", "99", AT.plusSeconds(3)));

        verify(matching, timeout(WAIT)).fill(filled(waiting.planId(), AT.plusSeconds(3)));
    }

    @Test
    void NSF07_aCommandLostToAnInterrupt_isLogged_andTheInterruptKept() {
        worker = new MatchingWorker(matching, properties(1, 1), clock, idle(), () -> 0.0);
        worker.start();
        worker.submit(range("99", "101", AT));

        Thread.currentThread().interrupt();
        worker.onCancelled(cancelled(UUID.randomUUID()));

        assertThat(Thread.interrupted()).isTrue();
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("Untrack").contains("lost");
        });
    }

    @Test
    void NSF07_afterStop_theConsumersEndAndNothingIsQueued() throws InterruptedException {
        worker = started(2, 100);

        worker.stop();
        for (Thread thread : started) {
            thread.join(WAIT);
        }

        assertThat(started).noneMatch(Thread::isAlive);
        assertThat(worker.submit(range("99", "101", AT))).isFalse();
        verify(matching, never()).fill(any());
    }

    private MatchingWorker started(int partitions, int capacity) {
        MatchingWorker started =
                new MatchingWorker(matching, properties(partitions, capacity), clock, recording, () -> 0.0);
        started.start();
        return started;
    }

    /** Runs the call on its own thread and returns once that thread waits for room in the channel. */
    private static Thread waitingIn(Runnable call) throws InterruptedException {
        Thread thread = Thread.ofPlatform().start(call);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
        return thread;
    }

    /** Consumers that never take from their channel, so a channel fills up. */
    private ThreadFactory idle() {
        return task -> recording.newThread(() -> {});
    }

    /** A Spot pair whose commands land in partition {@code index} of {@code count}, by the worker's own rule. */
    private static UUID pairIn(int index, int count) {
        while (true) {
            UUID pair = UUID.randomUUID();
            if (Math.floorMod(Objects.hash(MarketType.SPOT, pair), count) == index) {
                return pair;
            }
        }
    }

    private static MatchingProperties retrying(Duration deadline) {
        return new MatchingProperties(
                true,
                1,
                100,
                new MatchingProperties.Retry(Duration.ofSeconds(1), Duration.ofSeconds(1), 0, deadline),
                60,
                Duration.ofSeconds(5),
                REPLAY);
    }

    /** Returns once every command queued so far is handled: a marker of another pair is filled after them. */
    private void awaitProcessed() {
        UUID marker = UUID.randomUUID();
        worker.onActivated(new TradingPlanActivated(
                marker, MarketType.SPOT, MARKER_PAIR, Direction.LONG, EntryType.LIMIT, BigDecimal.ONE, ACTIVATED));
        worker.submit(new PriceRange(MarketType.SPOT, MARKER_PAIR, new BigDecimal("0.5"), new BigDecimal("1.5"), AT));
        verify(matching, timeout(WAIT)).fill(filledPlan(marker));
    }

    private static MatchingProperties properties(int partitions, int capacity) {
        return new MatchingProperties(true, partitions, capacity, RETRY_AT_ONCE, 60, Duration.ofSeconds(5), REPLAY);
    }

    private static TrackedEntry entry(UUID planId, Direction direction, String price) {
        return new TrackedEntry(planId, MarketType.SPOT, PAIR, direction, new BigDecimal(price), ACTIVATED);
    }

    private static TradingPlanActivated activated(UUID planId, EntryType type, String price) {
        return new TradingPlanActivated(
                planId, MarketType.SPOT, PAIR, Direction.LONG, type, new BigDecimal(price), ACTIVATED);
    }

    /** An update of the candle that opened at {@code minute}, produced {@code seconds} into it. */
    private static PriceRange update(String low, String high, Instant minute, long seconds) {
        return new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal(low), new BigDecimal(high), minute, minute.plusSeconds(seconds));
    }

    /** A fill of the plan recorded at {@code executedAt}. */
    private static Fill filled(UUID planId, Instant executedAt) {
        return argThat(fill -> fill != null
                && fill.planId().equals(planId)
                && fill.executedAt().equals(executedAt));
    }

    private static Fill filledPlan(UUID planId) {
        return argThat(fill -> fill != null && fill.planId().equals(planId));
    }

    private static Fill filledAt(Instant executedAt) {
        return argThat(fill -> fill != null && fill.executedAt().equals(executedAt));
    }

    private static TradingPlanCancelled cancelled(UUID planId) {
        return new TradingPlanCancelled(planId, MarketType.SPOT, PAIR);
    }

    /** The closed candle that opened at {@code open}. */
    private static PriceRange closed(String low, String high, Instant open) {
        return new PriceRange(
                MarketType.SPOT,
                PAIR,
                new BigDecimal(low),
                new BigDecimal(high),
                open,
                open.plusSeconds(60).minusMillis(1),
                true);
    }

    private static PriceRange range(String low, String high, Instant at) {
        return new PriceRange(MarketType.SPOT, PAIR, new BigDecimal(low), new BigDecimal(high), at);
    }

    /** Holds the consumer inside a fill until the test lets it go. */
    private static final class Gate {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        Object pass(org.mockito.invocation.InvocationOnMock call) throws InterruptedException {
            entered.countDown();
            released.await(WAIT, TimeUnit.MILLISECONDS);
            return true;
        }

        void awaitEntered() throws InterruptedException {
            assertThat(entered.await(WAIT, TimeUnit.MILLISECONDS)).isTrue();
        }

        void release() {
            released.countDown();
        }
    }
}
