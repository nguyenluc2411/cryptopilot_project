package com.cryptopilot.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The indicator values of a pair's latest closed candle on one timeframe, for a module that stores them beside a fill.
 * A value is {@code null} while its indicator is still warming up.
 *
 * <p>Rule: BR-12, BR-70; D-111 (M4), D-112.
 *
 * @param timeframe the timeframe, as BR-08 spells it
 * @param openTime the open time of the candle the values are for
 * @param rsi14 RSI14
 * @param ema20 EMA20
 * @param ema50 EMA50
 * @param ema200 EMA200
 * @param macdLine the MACD line
 * @param macdSignal the MACD signal line
 * @param macdHistogram the MACD histogram
 */
public record ClosedCandleIndicators(
        String timeframe,
        Instant openTime,
        BigDecimal rsi14,
        BigDecimal ema20,
        BigDecimal ema50,
        BigDecimal ema200,
        BigDecimal macdLine,
        BigDecimal macdSignal,
        BigDecimal macdHistogram) {

    public ClosedCandleIndicators {
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(openTime, "openTime");
    }
}
