package com.cryptopilot.trading.model;

import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * The exit levels of an open position, as the exit rule reads them.
 *
 * <p>Rule: BR-35; TECHNICAL_DESIGN 7.7.
 *
 * @param planId the plan the position came from
 * @param direction LONG or SHORT
 * @param takeProfit the take profit, or {@code null}
 * @param stopLoss the stop loss, or {@code null}
 * @param liquidationPrice the liquidation price of a Futures position, or {@code null} (Spot, or a LONG that cannot
 *     be liquidated)
 */
public record OpenPosition(
        UUID planId, Direction direction, BigDecimal takeProfit, BigDecimal stopLoss, BigDecimal liquidationPrice) {

    public OpenPosition {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(direction, "direction");
    }
}
