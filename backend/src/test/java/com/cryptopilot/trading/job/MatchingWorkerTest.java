package com.cryptopilot.trading.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import com.cryptopilot.trading.config.MatchingProperties;
import com.cryptopilot.trading.event.TradingPlanActivated;
import com.cryptopilot.trading.event.TradingPlanCancelled;
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

    private final MatchingService matching = mock(MatchingService.class);
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
        worker = new MatchingWorker(matching, new MatchingProperties(false, 1, 1), recording);

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
        worker = new MatchingWorker(matching, new MatchingProperties(true, 3, 10), recording);

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
        worker = new MatchingWorker(matching, new MatchingProperties(true, 1, 2), recording);

        assertTimeoutPreemptively(Duration.ofSeconds(5), worker::start);
        worker.submit(range("99", "101", AT));

        verify(matching, timeout(WAIT).times(50)).fill(any(), eq(AT));
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

        verify(matching, timeout(WAIT)).fill(entry.planId(), AT);
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

        verify(matching, timeout(WAIT)).fill(kept.planId(), AT);
        verify(matching, never()).fill(gone.planId(), AT);
    }

    @Test
    void NSF07_aLimitPlanActivated_isTracked_aMarketPlanIsNot() {
        UUID limit = UUID.randomUUID();
        UUID market = UUID.randomUUID();
        worker = started(1, 100);

        worker.onActivated(activated(market, EntryType.MARKET, "100"));
        worker.onActivated(activated(limit, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT));

        verify(matching, timeout(WAIT)).fill(limit, AT);
        verify(matching, never()).fill(market, AT);
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

        verify(matching, timeout(WAIT)).fill(marker, AT);
        verify(matching, never()).fill(cancelled, AT);
    }

    @Test
    void NSF07_aCancel_goesToItsPairsPartitionOnly_soFullPartitionsDoNotDelayIt() {
        int partitions = 4;
        UUID cancelledPair = pairIn(0, partitions);
        worker = new MatchingWorker(matching, new MatchingProperties(true, partitions, 1), idle());
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
        when(matching.fill(lost, AT)).thenReturn(false);
        worker = started(1, 100);
        worker.onActivated(activated(lost, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT));

        worker.onActivated(activated(marker, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT));

        verify(matching, timeout(WAIT)).fill(marker, AT);
        verify(matching, times(1)).fill(lost, AT);
    }

    @Test
    void NSF07_aFailedFill_isRetriedOnThePairsNextRange_atItsOriginalTime_evenIfThePriceMovedAway() {
        UUID plan = UUID.randomUUID();
        Instant later = AT.plusSeconds(60);
        when(matching.fill(plan, AT))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(true);
        worker = started(1, 100);
        worker.onActivated(activated(plan, EntryType.LIMIT, "100"));

        worker.submit(range("99", "101", AT));
        worker.submit(range("150", "160", later));

        verify(matching, timeout(WAIT).times(2)).fill(plan, AT);
        verify(matching, never()).fill(plan, later);
    }

    @Test
    void NSF07_aRangeOfAnotherPair_doesNotRetryAFailedFill() {
        UUID plan = UUID.randomUUID();
        UUID marker = UUID.randomUUID();
        UUID otherPair = UUID.randomUUID();
        when(matching.fill(plan, AT)).thenThrow(new IllegalStateException("database down"));
        worker = started(1, 100);
        worker.onActivated(activated(plan, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT));

        worker.onActivated(new TradingPlanActivated(
                marker, MarketType.SPOT, otherPair, Direction.LONG, EntryType.LIMIT, new BigDecimal("100")));
        worker.submit(new PriceRange(MarketType.SPOT, otherPair, new BigDecimal("99"), new BigDecimal("101"), AT));

        verify(matching, timeout(WAIT)).fill(marker, AT);
        verify(matching, times(1)).fill(plan, AT);
    }

    @Test
    void NSF07_aCancelledPlan_leavesTheRetryList() {
        UUID plan = UUID.randomUUID();
        UUID marker = UUID.randomUUID();
        when(matching.fill(plan, AT)).thenThrow(new IllegalStateException("database down"));
        worker = started(1, 100);
        worker.onActivated(activated(plan, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT));

        worker.onCancelled(cancelled(plan));
        worker.onActivated(activated(marker, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT));

        verify(matching, timeout(WAIT)).fill(marker, AT);
        verify(matching, times(1)).fill(plan, AT);
    }

    /**
     * The consumer is held inside a fill while the channel (capacity 2) fills up; the later ranges of the pair are
     * merged and matched once the channel is empty. Each merged range alone would fire one of the entries; the merge
     * fires them all, at the latest time.
     */
    @Test
    void NSF07_rangesForAFullChannel_areMergedPerPair_andStillReachEveryLevel() throws InterruptedException {
        TrackedEntry first = entry(UUID.randomUUID(), Direction.LONG, "100");
        TrackedEntry second = entry(UUID.randomUUID(), Direction.LONG, "98");
        TrackedEntry byLow4 = entry(UUID.randomUUID(), Direction.LONG, "95");
        TrackedEntry byLow5 = entry(UUID.randomUUID(), Direction.LONG, "90");
        TrackedEntry byHigh4 = entry(UUID.randomUUID(), Direction.SHORT, "110");
        when(matching.activeEntries()).thenReturn(List.of(first, second, byLow4, byLow5, byHigh4));
        Gate inFirst = new Gate();
        Gate inSecond = new Gate();
        doAnswer(inFirst::pass).when(matching).fill(eq(first.planId()), any());
        doAnswer(inSecond::pass).when(matching).fill(eq(second.planId()), any());
        when(matching.fill(eq(byLow4.planId()), any())).thenReturn(true);
        worker = started(1, 2);

        worker.submit(range("99", "101", AT));
        inFirst.awaitEntered();
        worker.submit(range("97.5", "99", AT.plusSeconds(1)));
        worker.submit(range("99.5", "100.5", AT.plusSeconds(2)));
        worker.submit(range("94", "111", AT.plusSeconds(4)));
        inFirst.release();
        inSecond.awaitEntered();
        worker.submit(range("89", "100", AT.plusSeconds(5)));
        inSecond.release();

        Instant merged = AT.plusSeconds(5);
        verify(matching, timeout(WAIT)).fill(byLow4.planId(), merged);
        verify(matching, timeout(WAIT)).fill(byLow5.planId(), merged);
        verify(matching, timeout(WAIT)).fill(byHigh4.planId(), merged);
        verify(matching, never()).fill(byLow4.planId(), AT.plusSeconds(4));
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
        doAnswer(held::pass).when(matching).fill(eq(holder.planId()), any());
        doAnswer(heldAgain::pass).when(matching).fill(eq(secondHolder.planId()), any());
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
        verify(matching, timeout(WAIT)).fill(eq(marker.planId()), any());
        verify(matching, never()).fill(eq(activated), any());
        worker.submit(range("80", "85", AT.plusSeconds(6)));
        verify(matching, timeout(WAIT)).fill(activated, AT.plusSeconds(6));
    }

    /** An activation already in the channel: the pair's next range follows it there and fills the new plan. */
    @Test
    void NSF07_aRangeAfterAQueuedActivation_followsItAndFillsTheNewPlan() throws InterruptedException {
        TrackedEntry holder = entry(UUID.randomUUID(), Direction.LONG, "100");
        when(matching.activeEntries()).thenReturn(List.of(holder));
        Gate held = new Gate();
        doAnswer(held::pass).when(matching).fill(eq(holder.planId()), any());
        UUID activated = UUID.randomUUID();
        worker = started(1, 10);
        worker.submit(range("99", "101", AT));
        held.awaitEntered();

        worker.onActivated(activated(activated, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101", AT.plusSeconds(1)));
        held.release();

        verify(matching, timeout(WAIT)).fill(activated, AT.plusSeconds(1));
    }

    /** A range that arrives while an activation waits for room in the channel is matched after it, and fills it. */
    @Test
    void NSF07_aRangeThatArrivesWhileAnActivationWaitsForRoom_isMatchedAfterIt() throws InterruptedException {
        TrackedEntry holder = entry(UUID.randomUUID(), Direction.LONG, "100");
        when(matching.activeEntries()).thenReturn(List.of(holder));
        Gate held = new Gate();
        doAnswer(held::pass).when(matching).fill(eq(holder.planId()), any());
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

        verify(matching, timeout(WAIT)).fill(activated, AT.plusSeconds(3));
    }

    /** A range that reached an entry, kept pending, then a cancel of that plan: the earlier range fills it first. */
    @Test
    void NSF07_aPendingRangeThatReachedAnEntryBeforeItsCancel_stillFillsIt() throws InterruptedException {
        TrackedEntry holder = entry(UUID.randomUUID(), Direction.LONG, "100");
        TrackedEntry cancelled = entry(UUID.randomUUID(), Direction.LONG, "98");
        when(matching.activeEntries()).thenReturn(List.of(holder, cancelled));
        Gate held = new Gate();
        doAnswer(held::pass).when(matching).fill(eq(holder.planId()), any());
        worker = started(1, 2);
        worker.submit(range("99", "101", AT));
        held.awaitEntered();
        worker.submit(range("150", "160", AT.plusSeconds(1)));
        worker.submit(range("150", "160", AT.plusSeconds(2)));
        worker.submit(range("97", "99", AT.plusSeconds(3)));

        Thread cancel = waitingIn(() -> worker.onCancelled(cancelled(cancelled.planId())));
        held.release();
        cancel.join(WAIT);

        verify(matching, timeout(WAIT)).fill(cancelled.planId(), AT.plusSeconds(3));
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
                .fill(failing, AT);
        worker = started(1, 100);
        worker.onActivated(activated(failing, EntryType.LIMIT, "100"));
        worker.onActivated(activated(other, EntryType.LIMIT, "100"));

        worker.submit(range("99", "101", AT));
        worker.submit(range("150", "160", AT.plusSeconds(60)));

        verify(matching, timeout(WAIT).times(2)).fill(failing, AT);
        verify(matching, timeout(WAIT)).fill(other, AT);
        assertThat(logs.list)
                .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("carries on"));
    }

    @Test
    void NSF07_aCommandLostToAnInterrupt_doesNotHoldBackItsPairsRanges() throws InterruptedException {
        TrackedEntry holder = entry(UUID.randomUUID(), Direction.LONG, "100");
        TrackedEntry waiting = entry(UUID.randomUUID(), Direction.LONG, "98");
        when(matching.activeEntries()).thenReturn(List.of(holder, waiting));
        Gate held = new Gate();
        doAnswer(held::pass).when(matching).fill(eq(holder.planId()), any());
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

        verify(matching, timeout(WAIT)).fill(waiting.planId(), AT.plusSeconds(3));
    }

    @Test
    void NSF07_aCommandLostToAnInterrupt_isLogged_andTheInterruptKept() {
        worker = new MatchingWorker(matching, new MatchingProperties(true, 1, 1), idle());
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
        verify(matching, never()).fill(any(), any());
    }

    private MatchingWorker started(int partitions, int capacity) {
        MatchingWorker started =
                new MatchingWorker(matching, new MatchingProperties(true, partitions, capacity), recording);
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

    private static TrackedEntry entry(UUID planId, Direction direction, String price) {
        return new TrackedEntry(planId, MarketType.SPOT, PAIR, direction, new BigDecimal(price));
    }

    private static TradingPlanActivated activated(UUID planId, EntryType type, String price) {
        return new TradingPlanActivated(planId, MarketType.SPOT, PAIR, Direction.LONG, type, new BigDecimal(price));
    }

    private static TradingPlanCancelled cancelled(UUID planId) {
        return new TradingPlanCancelled(planId, MarketType.SPOT, PAIR);
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
