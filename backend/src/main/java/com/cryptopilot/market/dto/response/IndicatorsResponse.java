package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;

/**
 * The indicators of one closed candle as stored, rounded to 10 places. An indicator still warming up is {@code null},
 * never zero (fewer than 200 closed candles leave EMA200 empty).
 *
 * <p>Rule: BR-12; SRS 3.3.2; TECHNICAL_DESIGN 7.2.
 *
 * @param sma20 SMA20
 * @param ema20 EMA20
 * @param ema50 EMA50
 * @param ema200 EMA200
 * @param rsi14 RSI14, Wilder smoothing
 * @param macdLine the MACD line (12, 26)
 * @param macdSignal the MACD signal (9)
 * @param macdHistogram the MACD histogram
 * @param bbUpper the upper Bollinger band
 * @param bbMiddle the middle Bollinger band
 * @param bbLower the lower Bollinger band
 * @param volumeSma20 the volume SMA20
 */
public record IndicatorsResponse(
        BigDecimal sma20,
        BigDecimal ema20,
        BigDecimal ema50,
        BigDecimal ema200,
        BigDecimal rsi14,
        BigDecimal macdLine,
        BigDecimal macdSignal,
        BigDecimal macdHistogram,
        BigDecimal bbUpper,
        BigDecimal bbMiddle,
        BigDecimal bbLower,
        BigDecimal volumeSma20) {

    /** Every indicator empty: the series has no stored row yet. */
    public static final IndicatorsResponse NONE =
            new IndicatorsResponse(null, null, null, null, null, null, null, null, null, null, null, null);
}
