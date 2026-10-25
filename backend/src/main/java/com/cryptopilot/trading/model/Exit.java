package com.cryptopilot.trading.model;

import com.cryptopilot.trading.model.enums.CloseReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An exit the exit rule has decided: why, at what price and when.
 *
 * <p>Rule: BR-35; TECHNICAL_DESIGN 7.7; D-78.
 *
 * @param planId the plan the position came from
 * @param reason TAKE_PROFIT, STOP_LOSS or LIQUIDATION
 * @param price the exit price before slippage: the take profit, the liquidation price, or the stop loss or the price
 *     the stop executed at when it was already passed (a gap); a stop loss is moved by the slippage when it is stored
 * @param exitedAt the open time of the candle that decided it, of the last prices or of the mark prices, so that a
 *     live update and the candle replayed later give the same exit
 */
public record Exit(UUID planId, CloseReason reason, BigDecimal price, Instant exitedAt) {

    public Exit {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(exitedAt, "exitedAt");
    }
}
