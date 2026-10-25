package com.cryptopilot.trading.service;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.PositionLevelChange;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.Invalidation;
import com.cryptopilot.trading.model.PositionExit;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.TrackedPosition;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The storage side of the matching engine: what the books start from and the outcomes they decide.
 *
 * <p>Rule: NSF-07, BR-34, BR-35, BR-68; TECHNICAL_DESIGN 5.5 and 7.7.
 */
public interface MatchingService {

    /** Every waiting plan: the ACTIVE LIMIT and STOP plans with their entry and invalidation levels. */
    List<TrackedEntry> activeEntries();

    /** Every OPEN simulated position with its current levels, read from the journal (D-119). */
    List<TrackedPosition> openPositions();

    /**
     * The level changes of every OPEN simulated position, oldest first per position, read from the journal: a candle
     * replayed after a restart that opened before a change is decided by the levels in force then (D-124).
     */
    Map<UUID, List<PositionLevelChange>> openLevelChanges();

    /**
     * Fills a plan's entry if it is still ACTIVE: the plan becomes EXECUTED at the fill's time, with its liquidity role
     * and its price, moved by {@code SLIPPAGE_BPS} against the Trader for a TAKER fill, and the journal records its OPEN
     * position at that stored price in the same transaction (BR-34). Nothing is written when the plan is no longer ACTIVE.
     *
     * @param fill the plan, the role, the price before slippage and the time
     * @return the position this call opened, for the exit books; empty when the plan was cancelled, expired,
     *     invalidated or filled first
     */
    Optional<TrackedPosition> fill(Fill fill);

    /**
     * Invalidates a plan if it is still ACTIVE: the plan becomes INVALIDATED at the time and with the reason, and
     * {@code TradingPlanInvalidated} is published for after the commit.
     *
     * @param invalidation the plan, the reason and the time
     * @return whether this call invalidated it; {@code false} when the plan was cancelled, expired, filled or
     *     invalidated first
     */
    boolean invalidate(Invalidation invalidation);

    /**
     * Closes a position if its journal row is still OPEN at the version the engine tracked: the exit, its realized P/L,
     * fees and funding are stored in one compare-and-set, and {@code PositionClosed} is published for after the commit
     * (BR-35).
     *
     * @return whether this call closed it; {@code false} when it was closed first, or a replay of the same exit
     */
    boolean close(PositionExit exit);

    /**
     * The open time of the pair's last 1-minute candle that was closed and fully matched, from which a restart replays
     * (A-04); empty when none was recorded.
     */
    Optional<Instant> watermark(MarketType market, UUID pairId);

    /** Records that the candle opened at {@code openTime} is closed and fully matched; never moves backwards. */
    void advanceWatermark(MarketType market, UUID pairId, Instant openTime);
}
