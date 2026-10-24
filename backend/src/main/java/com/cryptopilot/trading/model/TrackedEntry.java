package com.cryptopilot.trading.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A waiting ACTIVE plan as the matching engine tracks it: its entry and the levels that invalidate it before the entry
 * fills, a LIMIT plan's take profit or a STOP plan's stop loss, and its own invalidation price.
 *
 * <p>Rule: NSF-07, BR-33, BR-68; TECHNICAL_DESIGN 7.7; D-111 (M1, M3).
 *
 * @param planId the plan
 * @param userId the plan's owner, named in the invalidation event
 * @param market the market the plan trades on
 * @param pairId the pair
 * @param direction LONG or SHORT
 * @param entryType LIMIT or STOP; a MARKET entry fills at activation and is never tracked
 * @param entryPrice the limit price, or the stop price of a STOP entry
 * @param stopLoss the stop loss; invalidates a STOP plan reached before its trigger
 * @param takeProfit the take profit; invalidates a LIMIT plan reached before its entry
 * @param invalidationPrice the plan's own invalidation price, or {@code null} for none
 * @param activatedAt when the plan became ACTIVE; a range whose candle opened before it never acts on the plan (D-77)
 */
public record TrackedEntry(
        UUID planId,
        UUID userId,
        MarketType market,
        UUID pairId,
        Direction direction,
        EntryType entryType,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal invalidationPrice,
        Instant activatedAt) {

    public TrackedEntry {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(entryType, "entryType");
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(stopLoss, "stopLoss");
        Objects.requireNonNull(takeProfit, "takeProfit");
        Objects.requireNonNull(activatedAt, "activatedAt");
        if (entryType == EntryType.MARKET) {
            throw new IllegalArgumentException("a MARKET entry fills at activation and never waits in the books");
        }
    }
}
