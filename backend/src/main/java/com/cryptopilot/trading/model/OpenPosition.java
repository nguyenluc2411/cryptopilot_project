package com.cryptopilot.trading.model;

import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The exit levels of an open position, as the exit rule reads them, and where and when the position started.
 *
 * <p>Rule: BR-35; TECHNICAL_DESIGN 7.7; Q-59.
 *
 * @param planId the plan the position came from
 * @param direction LONG or SHORT
 * @param entryPrice the stored entry fill price; inside the entry candle it is the price the position starts from
 * @param entryTime when the entry counts as filled (D-78): a candle's open time, or the activation instant
 * @param takeProfit the take profit, or {@code null}
 * @param stopLoss the stop loss, or {@code null}
 * @param liquidationPrice the liquidation price of a Futures position, or {@code null} (Spot, or a LONG that cannot
 *     be liquidated)
 */
public record OpenPosition(
        UUID planId,
        Direction direction,
        BigDecimal entryPrice,
        Instant entryTime,
        BigDecimal takeProfit,
        BigDecimal stopLoss,
        BigDecimal liquidationPrice) {

    public OpenPosition {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(entryTime, "entryTime");
    }
}
