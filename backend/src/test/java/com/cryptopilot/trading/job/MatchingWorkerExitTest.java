package com.cryptopilot.trading.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.config.MatchingProperties;
import com.cryptopilot.trading.event.PositionClosed;
import com.cryptopilot.trading.event.PositionOpened;
import com.cryptopilot.trading.event.TradingPlanCancelled;
import com.cryptopilot.trading.event.TradingPlanExpired;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.PositionExit;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntries;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.service.MatchingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The worker's side of the exits: the positions rebuilt at the start, the position a stored fill opens and the one a
 * fill at activation opens, the exits stored and retried as the other outcomes, the mark prices beside the last prices,
 * and the watermark that follows the last price candles only.
 *
 * <p>Rule: NSF-07, BR-35; TECHNICAL_DESIGN 7.7; A-04; D-78; Q-59; the #70 review (one outcome type, one retry path).
 */
class MatchingWorkerExitTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000d001");
    private static final Instant AT = Instant.parse("2026-10-03T08:00:00Z");
    private static final Instant EARLIER = AT.minusSeconds(3600);
    private static final long WAIT = Duration.ofSeconds(5).toMillis();

    private static final MatchingProperties.Retry RETRY_AT_ONCE =
            new MatchingProperties.Retry(Duration.ZERO, Duration.ZERO, 0, Duration.ofMinutes(10));

    private final MatchingService matching = mock(MatchingService.class);
    private final Clock clock = Clock.fixed(AT.plusSeconds(30), ZoneOffset.UTC);
    private MatchingWorker worker;

    @AfterEach
    void stop() {
        if (worker != null) {
            worker.stop();
        }
    }

    @Test
    void NSF07_theStart_rebuildsTheOpenPositions_andARangeExitsThem() {
        TrackedPosition open = position(Direction.LONG, "100", EARLIER, "95", "115", null);
        when(matching.openPositions()).thenReturn(List.of(open));
        when(matching.close(any())).thenReturn(true);
        worker = started(1, 100);

        worker.submit(futures(AT, "100", "94", "101", false));

        verify(matching, timeout(WAIT)).close(exitOf(open, CloseReason.STOP_LOSS, "95"));
    }

    /** Q-59: a STOP the candle filled at its open reaches its stop loss in that candle: the same range exits it. */
    @Test
    void Q59_aStoredFill_opensItsPosition_andTheRangeThatFilledItCanExitIt() {
        TrackedEntry stop = TrackedEntries.of(
                UUID.randomUUID(),
                MarketType.FUTURES,
                PAIR,
                Direction.LONG,
                EntryType.STOP,
                new BigDecimal("105"),
                new BigDecimal("100"),
                new BigDecimal("130"),
                null,
                EARLIER);
        when(matching.activeEntries()).thenReturn(List.of(stop));
        TrackedPosition opened = new TrackedPosition(
                UUID.randomUUID(),
                stop.planId(),
                TrackedEntries.OWNER,
                MarketType.FUTURES,
                PAIR,
                Direction.LONG,
                new BigDecimal("106.1"),
                AT,
                new BigDecimal("100"),
                new BigDecimal("130"),
                null,
                0);
        when(matching.fill(any())).thenReturn(Optional.of(opened));
        when(matching.close(any())).thenReturn(true);
        worker = started(1, 100);

        worker.submit(futures(AT, "106", "99", "107", true));

        verify(matching, timeout(WAIT))
                .fill(argThat((Fill fill) -> fill.planId().equals(stop.planId())
                        && fill.liquidity() == LiquidityRole.TAKER
                        && fill.price().compareTo(new BigDecimal("106")) == 0));
        verify(matching, timeout(WAIT)).close(exitOf(opened, CloseReason.STOP_LOSS, "100"));
        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.FUTURES, PAIR, AT);
    }

    /** BR-35: a plan filled at activation is added to the exit books after the commit and exits on a later range. */
    @Test
    void BR35_aPositionOpenedAtActivation_entersTheExitBooks() {
        when(matching.close(any())).thenReturn(true);
        worker = started(1, 100);
        TrackedPosition position = position(Direction.SHORT, "100", EARLIER, "105", "90", null);

        worker.onPositionOpened(opened(position));
        worker.submit(futures(AT, "95", "89", "96", false));

        verify(matching, timeout(WAIT)).close(exitOf(position, CloseReason.TAKE_PROFIT, "90"));
    }

    @Test
    void BR35_aPositionOpenedWhileTheEngineIsStopped_isIgnored() {
        worker = new MatchingWorker(
                matching, properties(1, 100), clock, Thread.ofVirtual().factory(), () -> 0.0);

        worker.onPositionOpened(opened(position(Direction.LONG, "100", EARLIER, "95", "115", null)));

        verify(matching, never()).close(any());
    }

    @Test
    void BR35_aMarkRange_liquidates_andNeverAdvancesTheWatermark() {
        TrackedPosition futures = position(Direction.LONG, "100", EARLIER, "50", "150", "90.37");
        when(matching.openPositions()).thenReturn(List.of(futures));
        when(matching.close(any())).thenReturn(true);
        worker = started(1, 100);

        worker.submit(PriceRange.mark(PAIR, new BigDecimal("90"), new BigDecimal("91"), AT, AT.plusSeconds(59), true));
        worker.submit(futures(AT, "100", "99", "101", true));

        verify(matching, timeout(WAIT)).close(exitOf(futures, CloseReason.LIQUIDATION, "90.37"));
        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.FUTURES, PAIR, AT);
        verify(matching, times(1)).advanceWatermark(any(), any(), any());
    }

    /** The #70 review: an exit that fails to be stored is retried on the pair's next range and holds the watermark. */
    @Test
    void NSF07_aFailedExit_isRetriedOnTheNextRange_andHoldsTheWatermarkMeanwhile() {
        TrackedPosition open = position(Direction.LONG, "100", EARLIER, "95", "115", null);
        when(matching.openPositions()).thenReturn(List.of(open));
        when(matching.close(any()))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(true);
        worker = started(1, 100);

        worker.submit(futures(AT, "100", "94", "101", true));
        worker.submit(futures(AT.plusSeconds(60), "100", "99", "101", true));
        worker.submit(futures(AT.plusSeconds(120), "100", "99", "101", true));

        verify(matching, timeout(WAIT).times(2)).close(exitOf(open, CloseReason.STOP_LOSS, "95"));
        verify(matching, timeout(WAIT)).advanceWatermark(MarketType.FUTURES, PAIR, AT.plusSeconds(120));
        verify(matching, never()).advanceWatermark(MarketType.FUTURES, PAIR, AT);
    }

    /** A cancel drops the plan's failed fills only: a failed exit of the same plan keeps waiting for its retry. */
    @Test
    void NSF07_aCancelOfAPlan_doesNotDropTheRetryOfAnExit() {
        TrackedPosition open = position(Direction.LONG, "100", EARLIER, "95", "115", null);
        when(matching.openPositions()).thenReturn(List.of(open));
        when(matching.close(any()))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(true);
        worker = started(1, 100);

        worker.submit(futures(AT, "100", "94", "101", false));
        verify(matching, timeout(WAIT)).close(any());
        worker.onCancelled(new TradingPlanCancelled(open.planId(), MarketType.FUTURES, PAIR));
        worker.submit(futures(AT.plusSeconds(60), "100", "99", "101", false));

        verify(matching, timeout(WAIT).times(2)).close(exitOf(open, CloseReason.STOP_LOSS, "95"));
    }

    /** A stored fill retried after a failure opens its position without a range, which the next range then reads. */
    @Test
    void NSF07_aFillStoredOnItsRetry_opensItsPositionForTheNextRanges() {
        TrackedEntry limit = TrackedEntries.limit(
                UUID.randomUUID(), MarketType.FUTURES, PAIR, Direction.LONG, new BigDecimal("100"), EARLIER);
        when(matching.activeEntries()).thenReturn(List.of(limit));
        TrackedPosition opened = new TrackedPosition(
                UUID.randomUUID(),
                limit.planId(),
                TrackedEntries.OWNER,
                MarketType.FUTURES,
                PAIR,
                Direction.LONG,
                new BigDecimal("100"),
                AT,
                new BigDecimal("97"),
                new BigDecimal("130"),
                null,
                0);
        when(matching.fill(any()))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(Optional.of(opened));
        when(matching.close(any())).thenReturn(true);
        worker = started(1, 100);

        worker.submit(futures(AT, "100", "99", "101", false));
        worker.submit(futures(AT.plusSeconds(60), "100", "99", "101", false));
        verify(matching, timeout(WAIT).times(2)).fill(any());
        worker.submit(futures(AT.plusSeconds(120), "100", "96", "101", false));

        verify(matching, timeout(WAIT)).close(exitOf(opened, CloseReason.STOP_LOSS, "97"));
    }

    /**
     * While the consumer is busy, the last and mark prices of one minute wait as one pending range of each kind and
     * both still act: the mark price liquidates one position, the last price stops another.
     */
    @Test
    void NSF07_pendingLastAndMarkPricesOfOneMinute_mergeEachWithItsKind() throws InterruptedException {
        TrackedEntry holder = TrackedEntries.limit(
                UUID.randomUUID(), MarketType.FUTURES, PAIR, Direction.LONG, new BigDecimal("100"), EARLIER);
        TrackedPosition liquidated = position(Direction.LONG, "100", EARLIER, "50", "150", "90.37");
        TrackedPosition stopped = position(Direction.LONG, "100", EARLIER, "97.5", "150", null);
        when(matching.activeEntries()).thenReturn(List.of(holder));
        when(matching.openPositions()).thenReturn(List.of(liquidated, stopped));
        CountDownLatch inFill = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
                    inFill.countDown();
                    release.await(WAIT, TimeUnit.MILLISECONDS);
                    return Optional.empty();
                })
                .when(matching)
                .fill(any());
        when(matching.close(any())).thenReturn(true);
        worker = started(1, 1);

        worker.submit(futures(AT, "100", "99.9", "100.1", false));
        inFill.await(WAIT, TimeUnit.MILLISECONDS);
        worker.submit(futures(AT, "100", "98", "100.1", false));
        worker.submit(PriceRange.mark(PAIR, new BigDecimal("95"), new BigDecimal("95"), AT, AT.plusSeconds(2), false));
        worker.submit(futures(AT, "100", "97", "100.1", false));
        worker.submit(PriceRange.mark(PAIR, new BigDecimal("90"), new BigDecimal("90"), AT, AT.plusSeconds(4), false));
        worker.submit(futures(AT, "100", "96", "100.1", false));
        release.countDown();

        verify(matching, timeout(WAIT)).close(exitOf(liquidated, CloseReason.LIQUIDATION, "90.37"));
        verify(matching, timeout(WAIT)).close(exitOf(stopped, CloseReason.STOP_LOSS, "97.5"));
    }

    /** UC-21: a position the Trader closed leaves the books: a later range at its stop loss stores nothing. */
    @Test
    void UC21_aPositionClosedByItsTrader_leavesTheBooks() {
        TrackedPosition open = position(Direction.LONG, "100", EARLIER, "95", "115", null);
        TrackedPosition other = position(Direction.LONG, "100", EARLIER, "96", "115", null);
        when(matching.openPositions()).thenReturn(List.of(open, other));
        when(matching.close(any())).thenReturn(true);
        worker = started(1, 100);

        worker.onPositionClosed(closed(open));
        worker.submit(futures(AT, "100", "94", "101", false));

        verify(matching, timeout(WAIT)).close(exitOf(other, CloseReason.STOP_LOSS, "96"));
        verify(matching, never()).close(exitOf(open, CloseReason.STOP_LOSS, "95"));
    }

    /**
     * UC-21: a position closed by its Trader while its exit waits for a retry drops that retry only: the failed exit
     * of another position and the failed fill of a plan are still tried on the next range.
     */
    @Test
    void UC21_aManualClose_dropsOnlyThePendingExitOfItsPosition() {
        TrackedPosition closedByHand = position(Direction.LONG, "100", EARLIER, "95", "115", null);
        TrackedPosition stillOpen = position(Direction.LONG, "100", EARLIER, "96", "115", null);
        TrackedEntry waiting = TrackedEntries.limit(
                UUID.randomUUID(), MarketType.FUTURES, PAIR, Direction.LONG, new BigDecimal("94.5"), EARLIER);
        when(matching.openPositions()).thenReturn(List.of(closedByHand, stillOpen));
        when(matching.activeEntries()).thenReturn(List.of(waiting));
        when(matching.close(any())).thenThrow(new IllegalStateException("database down"));
        when(matching.fill(any())).thenThrow(new IllegalStateException("database down"));
        worker = started(1, 100);
        worker.submit(futures(AT, "100", "94", "101", false));
        verify(matching, timeout(WAIT).times(2)).close(any());
        verify(matching, timeout(WAIT)).fill(any());

        worker.onPositionClosed(closed(closedByHand));
        worker.submit(futures(AT.plusSeconds(60), "100", "99", "101", false));

        verify(matching, timeout(WAIT).times(2)).close(exitOf(stillOpen, CloseReason.STOP_LOSS, "96"));
        verify(matching, timeout(WAIT).times(2)).fill(any());
        verify(matching, times(1)).close(exitOf(closedByHand, CloseReason.STOP_LOSS, "95"));
    }

    /** NSF-09: an expired plan leaves the books: a later range at its entry fills nothing. */
    @Test
    void NSF09_anExpiredPlan_leavesTheBooks() {
        TrackedEntry expired = TrackedEntries.limit(
                UUID.randomUUID(), MarketType.FUTURES, PAIR, Direction.LONG, new BigDecimal("100"), EARLIER);
        TrackedEntry kept = TrackedEntries.limit(
                UUID.randomUUID(), MarketType.FUTURES, PAIR, Direction.LONG, new BigDecimal("99.5"), EARLIER);
        when(matching.activeEntries()).thenReturn(List.of(expired, kept));
        worker = started(1, 100);

        worker.onExpired(new TradingPlanExpired(
                expired.planId(), TrackedEntries.OWNER, MarketType.FUTURES, PAIR, Direction.LONG, null, AT));
        worker.submit(futures(AT, "100", "99", "101", false));

        verify(matching, timeout(WAIT))
                .fill(argThat((Fill fill) -> fill.planId().equals(kept.planId())));
        verify(matching, never()).fill(argThat((Fill fill) -> fill.planId().equals(expired.planId())));
    }

    @Test
    void NSF09_eventsWhileTheEngineIsStopped_areIgnored() {
        worker = new MatchingWorker(
                matching, properties(1, 100), clock, Thread.ofVirtual().factory(), () -> 0.0);
        TrackedPosition open = position(Direction.LONG, "100", EARLIER, "95", "115", null);

        worker.onPositionClosed(closed(open));
        worker.onExpired(new TradingPlanExpired(
                UUID.randomUUID(), TrackedEntries.OWNER, MarketType.FUTURES, PAIR, Direction.LONG, null, AT));

        verify(matching, never()).close(any());
    }

    private static PositionClosed closed(TrackedPosition position) {
        return new PositionClosed(
                position.positionId(),
                position.planId(),
                position.userId(),
                position.market(),
                position.pairId(),
                "BTCUSDT",
                CloseReason.MANUAL,
                new BigDecimal("100"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                AT);
    }

    private MatchingWorker started(int partitions, int capacity) {
        MatchingWorker started = new MatchingWorker(
                matching,
                properties(partitions, capacity),
                clock,
                Thread.ofVirtual().factory(),
                () -> 0.0);
        started.start();
        return started;
    }

    private static MatchingProperties properties(int partitions, int capacity) {
        return new MatchingProperties(
                true,
                partitions,
                capacity,
                RETRY_AT_ONCE,
                60,
                Duration.ofSeconds(5),
                new MatchingProperties.Replay(false, Duration.ofHours(24), Duration.ZERO, 0, 1440));
    }

    private static PositionExit exitOf(TrackedPosition position, CloseReason reason, String price) {
        return argThat(exit -> exit != null
                && exit.position().positionId().equals(position.positionId())
                && exit.exit().reason() == reason
                && exit.exit().price().compareTo(new BigDecimal(price)) == 0);
    }

    private static PositionOpened opened(TrackedPosition position) {
        return new PositionOpened(
                position.positionId(),
                position.planId(),
                position.userId(),
                position.market(),
                position.pairId(),
                position.direction(),
                position.entryPrice(),
                position.entryTime(),
                position.stopLoss(),
                position.takeProfit(),
                position.liquidationPrice(),
                position.version());
    }

    private static TrackedPosition position(
            Direction direction, String entry, Instant entryTime, String stop, String target, String liquidation) {
        return new TrackedPosition(
                UUID.randomUUID(),
                UUID.randomUUID(),
                TrackedEntries.OWNER,
                MarketType.FUTURES,
                PAIR,
                direction,
                new BigDecimal(entry),
                entryTime,
                new BigDecimal(stop),
                new BigDecimal(target),
                liquidation == null ? null : new BigDecimal(liquidation),
                0);
    }

    private static PriceRange futures(Instant minute, String open, String low, String high, boolean closed) {
        return new PriceRange(
                MarketType.FUTURES,
                PAIR,
                new BigDecimal(open),
                new BigDecimal(low),
                new BigDecimal(high),
                minute,
                minute.plusSeconds(closed ? 59 : 1),
                closed);
    }
}
