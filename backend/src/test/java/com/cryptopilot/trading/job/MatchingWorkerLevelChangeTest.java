package com.cryptopilot.trading.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.PositionLevelChange;
import com.cryptopilot.trading.config.MatchingProperties;
import com.cryptopilot.trading.event.PositionLevelsChanged;
import com.cryptopilot.trading.model.PositionExit;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntries;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.service.MatchingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * TP-E, the worker's side of a change of an open position's levels: the change committed replaces the levels in the
 * exit books in the order of the pair's ranges, and a restart rebuilds the history of the changes, so the candles it
 * replays from before a change are decided by the levels in force then.
 *
 * <p>Rule: NSF-07, BR-35; A-04; D-77, D-124.
 */
class MatchingWorkerLevelChangeTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000d101");
    private static final Instant AT = Instant.parse("2026-10-25T08:00:00Z");
    private static final Instant EARLIER = AT.minusSeconds(3600);
    private static final long WAIT = Duration.ofSeconds(5).toMillis();

    private final MatchingService matching = mock(MatchingService.class);
    private final Clock clock = Clock.fixed(AT.plusSeconds(30), ZoneOffset.UTC);
    private MatchingWorker worker;

    @AfterEach
    void stop() {
        if (worker != null) {
            worker.stop();
        }
    }

    /** A change committed before the next minute: that minute exits at the new stop, at the row's new version. */
    @Test
    void D124_aCommittedChange_replacesTheLevelsInTheExitBooks() {
        TrackedPosition open = position(0, "95", "115");
        when(matching.openPositions()).thenReturn(List.of(open));
        when(matching.close(any())).thenReturn(true);
        worker = started();
        TrackedPosition changed = position(open, 1, "98", "115");

        worker.onLevelsChanged(new PositionLevelsChanged(changed, change(open, "95", "98", AT.plusSeconds(10))));
        worker.submit(futures(AT.plusSeconds(60), "100", "97.5", "101", false));

        verify(matching, timeout(WAIT)).close(exitAt(changed, 1, "98"));
    }

    /** While the engine is stopped a change is not queued: the next start reads it from the journal. */
    @Test
    void D124_aChangeWhileTheEngineIsStopped_isIgnored() {
        worker = new MatchingWorker(
                matching, properties(), clock, Thread.ofVirtual().factory(), () -> 0.0);
        TrackedPosition open = position(0, "95", "115");

        worker.onLevelsChanged(new PositionLevelsChanged(open, change(open, "95", "98", AT)));

        verify(matching, never()).close(any());
    }

    /**
     * A restart rebuilds the history from the journal: a candle replayed from before the change is decided by the
     * earlier stop 95, which the new stop 98 would have closed earlier.
     */
    @Test
    void D124_theStart_rebuildsTheHistory_soAnOlderReplayedCandleUsesTheEarlierLevels() {
        TrackedPosition current = position(1, "98", "115");
        when(matching.openPositions()).thenReturn(List.of(current));
        when(matching.openLevelChanges())
                .thenReturn(Map.of(current.positionId(), List.of(change(current, "95", "98", AT.plusSeconds(90)))));
        when(matching.close(any())).thenReturn(true);
        worker = started();

        worker.submit(futures(AT, "100", "97", "101", true));
        verify(matching, after(500).never()).close(any());

        worker.submit(futures(AT.plusSeconds(60), "100", "94.5", "101", true));
        verify(matching, timeout(WAIT)).close(exitAt(current, 1, "95"));
    }

    private MatchingWorker started() {
        MatchingWorker started = new MatchingWorker(
                matching, properties(), clock, Thread.ofVirtual().factory(), () -> 0.0);
        started.start();
        return started;
    }

    private static MatchingProperties properties() {
        return new MatchingProperties(
                true,
                1,
                100,
                new MatchingProperties.Retry(Duration.ZERO, Duration.ZERO, 0, Duration.ofMinutes(10)),
                60,
                Duration.ofSeconds(5),
                new MatchingProperties.Replay(false, Duration.ofHours(24), Duration.ZERO, 0, 1440));
    }

    private static PositionExit exitAt(TrackedPosition position, long version, String price) {
        return argThat(exit -> exit != null
                && exit.position().positionId().equals(position.positionId())
                && exit.position().version() == version
                && exit.exit().reason() == CloseReason.STOP_LOSS
                && exit.exit().price().compareTo(new BigDecimal(price)) == 0);
    }

    private static PositionLevelChange change(TrackedPosition position, String oldStop, String newStop, Instant at) {
        return new PositionLevelChange(
                position.positionId(),
                at,
                new BigDecimal(oldStop),
                position.takeProfit(),
                new BigDecimal(newStop),
                position.takeProfit());
    }

    private static TrackedPosition position(long version, String stop, String target) {
        return position(
                new TrackedPosition(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        TrackedEntries.OWNER,
                        MarketType.FUTURES,
                        PAIR,
                        Direction.LONG,
                        new BigDecimal("100"),
                        EARLIER,
                        null,
                        null,
                        null,
                        0),
                version,
                stop,
                target);
    }

    private static TrackedPosition position(TrackedPosition of, long version, String stop, String target) {
        return new TrackedPosition(
                of.positionId(),
                of.planId(),
                of.userId(),
                of.market(),
                of.pairId(),
                of.direction(),
                of.entryPrice(),
                of.entryTime(),
                new BigDecimal(stop),
                new BigDecimal(target),
                of.liquidationPrice(),
                version);
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
