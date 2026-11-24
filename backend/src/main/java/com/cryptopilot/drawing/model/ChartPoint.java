package com.cryptopilot.drawing.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One anchor of a drawing: the open time of a candle and a price. A drawing is anchored by time rather than by the
 * position of a bar, so it stays in place when the Trader changes the timeframe or older candles are loaded, and it may
 * lie to the right of the last candle.
 *
 * <p>Rule: D-149 (4); T-119.
 *
 * @param time the candle open time
 * @param price the price, greater than 0
 */
public record ChartPoint(Instant time, BigDecimal price) {

    public ChartPoint {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(price, "price");
    }
}
