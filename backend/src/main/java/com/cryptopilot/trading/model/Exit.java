package com.cryptopilot.trading.model;

import com.cryptopilot.trading.model.enums.CloseReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An exit the exit rule has decided: why, at what price and when.
 *
 * <p>Rule: BR-35; TECHNICAL_DESIGN 7.7.
 *
 * @param planId the plan the position came from
 * @param reason TAKE_PROFIT, STOP_LOSS or LIQUIDATION
 * @param price the exit price: the level that was reached
 * @param exitedAt the open time of the candle for a range, the time of the mark price for a liquidation
 */
public record Exit(UUID planId, CloseReason reason, BigDecimal price, Instant exitedAt) {

    public Exit {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(exitedAt, "exitedAt");
    }
}
