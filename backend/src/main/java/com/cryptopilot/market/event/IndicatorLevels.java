package com.cryptopilot.market.event;

import java.math.BigDecimal;
import org.springframework.modulith.NamedInterface;

/**
 * The indicator values of one stored candle that alerts read, as stored (10 places); a value is {@code null} during
 * warm-up or when the row is missing.
 *
 * <p>Rule: NSF-05, NSF-06; BR-12; TECHNICAL_DESIGN 7.9.
 *
 * @param rsi14 RSI14
 * @param macdHistogram the MACD line minus its signal
 * @param ema20 EMA20
 * @param ema50 EMA50
 */
@NamedInterface("events")
public record IndicatorLevels(BigDecimal rsi14, BigDecimal macdHistogram, BigDecimal ema20, BigDecimal ema50) {

    /** No values: the row of the candle is not stored. */
    public static final IndicatorLevels NONE = new IndicatorLevels(null, null, null, null);
}
