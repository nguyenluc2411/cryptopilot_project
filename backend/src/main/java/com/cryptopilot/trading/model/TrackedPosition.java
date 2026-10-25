package com.cryptopilot.trading.model;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An OPEN simulated position as the matching engine tracks it: its exit levels as the journal row holds them, and the
 * row's version they were read at. A change of the levels (TP-E) commits a new version and replaces the tracked
 * position, as an activation adds a plan.
 *
 * <p>Rule: BR-35, NSF-07; TECHNICAL_DESIGN 7.7; D-119.
 *
 * @param positionId the journal record
 * @param planId the plan that filled
 * @param userId the owner
 * @param market SPOT or FUTURES
 * @param pairId the pair
 * @param direction LONG or SHORT
 * @param entryPrice the stored entry fill price
 * @param entryTime when the entry counts as filled (D-78)
 * @param stopLoss the current stop loss
 * @param takeProfit the current take profit
 * @param liquidationPrice the liquidation price of a Futures position, or {@code null}
 * @param version the journal row's version the levels were read at
 */
public record TrackedPosition(
        UUID positionId,
        UUID planId,
        UUID userId,
        MarketType market,
        UUID pairId,
        Direction direction,
        BigDecimal entryPrice,
        Instant entryTime,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal liquidationPrice,
        long version) {

    public TrackedPosition {
        Objects.requireNonNull(positionId, "positionId");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(entryTime, "entryTime");
    }

    /** The OPEN journal record as the engine tracks it. */
    public static TrackedPosition of(OpenTrade trade) {
        return new TrackedPosition(
                trade.positionId(),
                trade.planId(),
                trade.userId(),
                trade.market(),
                trade.pairId(),
                trade.direction(),
                trade.entryPrice(),
                trade.entryTime(),
                trade.stopLoss(),
                trade.takeProfit(),
                trade.liquidationPrice(),
                trade.version());
    }

    /** The levels the exit rule reads. */
    public OpenPosition levels() {
        return new OpenPosition(planId, direction, entryPrice, entryTime, takeProfit, stopLoss, liquidationPrice);
    }
}
