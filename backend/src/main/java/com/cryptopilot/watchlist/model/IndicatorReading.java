package com.cryptopilot.watchlist.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The value an indicator alert compares, on the closed candle and on the candle before it in the same series.
 *
 * <p>Rule: BR-18, BR-20.
 *
 * @param previous the value on the candle before, or {@code null} when it is not known; a cross then does not fire
 * @param current the value on the closed candle
 */
public record IndicatorReading(BigDecimal previous, BigDecimal current) {

    public IndicatorReading {
        Objects.requireNonNull(current, "current");
    }
}
