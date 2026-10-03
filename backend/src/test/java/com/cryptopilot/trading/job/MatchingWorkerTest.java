package com.cryptopilot.trading.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.config.MatchingProperties;
import com.cryptopilot.trading.event.TradingPlanActivated;
import com.cryptopilot.trading.event.TradingPlanCancelled;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.service.MatchingService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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

    private MatchingWorker worker;

    @AfterEach
    void stop() throws InterruptedException {
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
        worker.onCancelled(new TradingPlanCancelled(UUID.randomUUID()));

        assertThat(worker.submit(range("90", "110"))).isFalse();
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
    void NSF07_anEntryLoadedAtStart_isFilledWhenARangeReachesIt() {
        TrackedEntry entry = entry(UUID.randomUUID(), "100");
        when(matching.activeEntries()).thenReturn(List.of(entry));
        worker = started(2);

        assertThat(worker.submit(range("99", "101"))).isTrue();

        verify(matching, timeout(WAIT)).fill(entry.planId(), AT);
    }

    @Test
    void NSF07_aLimitPlanActivated_isTracked_aMarketPlanIsNot() {
        UUID limit = UUID.randomUUID();
        UUID market = UUID.randomUUID();
        worker = started(1);

        worker.onActivated(activated(market, EntryType.MARKET, "100"));
        worker.onActivated(activated(limit, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101"));

        verify(matching, timeout(WAIT)).fill(limit, AT);
        verify(matching, never()).fill(market, AT);
    }

    @Test
    void NSF07_aCancelledPlan_leavesTheBooks() {
        UUID cancelled = UUID.randomUUID();
        UUID marker = UUID.randomUUID();
        worker = started(1);
        worker.onActivated(activated(cancelled, EntryType.LIMIT, "100"));

        worker.onCancelled(new TradingPlanCancelled(cancelled));
        worker.onActivated(activated(marker, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101"));

        verify(matching, timeout(WAIT)).fill(marker, AT);
        verify(matching, never()).fill(cancelled, AT);
    }

    @Test
    void NSF07_anEntryThatLostTheRace_isNotTriedAgain() {
        UUID lost = UUID.randomUUID();
        UUID marker = UUID.randomUUID();
        when(matching.fill(lost, AT)).thenReturn(false);
        worker = started(1);
        worker.onActivated(activated(lost, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101"));

        worker.onActivated(activated(marker, EntryType.LIMIT, "100"));
        worker.submit(range("99", "101"));

        verify(matching, timeout(WAIT)).fill(marker, AT);
        verify(matching, times(1)).fill(lost, AT);
    }

    @Test
    void NSF07_anEntryWhoseFillFailed_staysInTheBooks() {
        UUID plan = UUID.randomUUID();
        when(matching.fill(plan, AT))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(true);
        worker = started(1);
        worker.onActivated(activated(plan, EntryType.LIMIT, "100"));

        worker.submit(range("99", "101"));
        worker.submit(range("99", "101"));

        verify(matching, timeout(WAIT).times(2)).fill(plan, AT);
    }

    @Test
    void NSF07_aRangeForAFullPartition_isDropped() {
        worker = new MatchingWorker(matching, new MatchingProperties(true, 1, 2), idle());
        worker.start();

        assertThat(worker.submit(range("99", "101"))).isTrue();
        assertThat(worker.submit(range("99", "101"))).isTrue();
        assertThat(worker.submit(range("99", "101"))).isFalse();
    }

    @Test
    void NSF07_aCommandThatCannotWait_keepsTheInterrupt() {
        worker = new MatchingWorker(matching, new MatchingProperties(true, 1, 1), idle());
        worker.start();
        worker.submit(range("99", "101"));

        Thread.currentThread().interrupt();
        worker.onCancelled(new TradingPlanCancelled(UUID.randomUUID()));

        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void NSF07_afterStop_theConsumersEndAndNothingIsQueued() throws InterruptedException {
        worker = started(2);

        worker.stop();
        for (Thread thread : started) {
            thread.join(WAIT);
        }

        assertThat(started).noneMatch(Thread::isAlive);
        assertThat(worker.submit(range("99", "101"))).isFalse();
        verify(matching, never()).fill(any(), any());
    }

    private MatchingWorker started(int partitions) {
        MatchingWorker started = new MatchingWorker(matching, new MatchingProperties(true, partitions, 100), recording);
        started.start();
        return started;
    }

    /** Consumers that never take from their queue, so a queue fills up. */
    private ThreadFactory idle() {
        return task -> recording.newThread(() -> {});
    }

    private static TrackedEntry entry(UUID planId, String price) {
        return new TrackedEntry(planId, MarketType.SPOT, PAIR, Direction.LONG, new BigDecimal(price));
    }

    private static TradingPlanActivated activated(UUID planId, EntryType type, String price) {
        return new TradingPlanActivated(planId, MarketType.SPOT, PAIR, Direction.LONG, type, new BigDecimal(price));
    }

    private static PriceRange range(String low, String high) {
        return new PriceRange(MarketType.SPOT, PAIR, new BigDecimal(low), new BigDecimal(high), AT);
    }
}
