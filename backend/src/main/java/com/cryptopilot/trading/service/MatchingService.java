package com.cryptopilot.trading.service;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.Invalidation;
import com.cryptopilot.trading.model.TrackedEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The storage side of the matching engine: what the books start from and the outcomes they decide.
 *
 * <p>Rule: NSF-07, BR-68; TECHNICAL_DESIGN 5.5 and 7.7.
 */
public interface MatchingService {

    /** Every waiting plan: the ACTIVE LIMIT and STOP plans with their entry and invalidation levels. */
    List<TrackedEntry> activeEntries();

    /**
     * Fills a plan's entry if it is still ACTIVE: the plan becomes EXECUTED at the fill's time, with its liquidity role
     * and its price, moved by {@code SLIPPAGE_BPS} against the Trader for a TAKER fill. The journal record of BR-34 is
     * T-048, which reads them.
     *
     * @param fill the plan, the role, the price before slippage and the time
     * @return whether this call filled it; {@code false} when the plan was cancelled, expired, invalidated or filled
     *     first
     */
    boolean fill(Fill fill);

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
     * The open time of the pair's last 1-minute candle that was closed and fully matched, from which a restart replays
     * (A-04); empty when none was recorded.
     */
    Optional<Instant> watermark(MarketType market, UUID pairId);

    /** Records that the candle opened at {@code openTime} is closed and fully matched; never moves backwards. */
    void advanceWatermark(MarketType market, UUID pairId, Instant openTime);
}
