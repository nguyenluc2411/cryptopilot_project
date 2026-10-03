package com.cryptopilot.trading.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The entry of an ACTIVE LIMIT plan as the matching engine tracks it.
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 7.7.
 *
 * @param planId the plan
 * @param market the market the plan trades on
 * @param pairId the pair
 * @param direction LONG waits below the price, SHORT above it
 * @param entryPrice the limit price
 * @param activatedAt when the plan became ACTIVE; a range whose candle opened before it never fills the plan (D-77)
 */
public record TrackedEntry(
        UUID planId, MarketType market, UUID pairId, Direction direction, BigDecimal entryPrice, Instant activatedAt) {

    public TrackedEntry {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(activatedAt, "activatedAt");
    }
}
