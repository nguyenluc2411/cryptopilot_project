package com.cryptopilot.market.model;

import java.time.Instant;

/**
 * The indicators of one series after its latest closed candle, computed in {@code double} (TECHNICAL_DESIGN 5.4: the
 * indicators are the one exception to exact decimals; they are rounded to 10 places only when stored).
 *
 * <p>A {@code null} value means there are not yet enough consecutive closed candles for that indicator — never a zero
 * or a partial average (SRS 3.3.2): SMA20, EMA20, the Bollinger Bands and the volume SMA20 need 20 candles, EMA50 needs
 * 50, EMA200 needs 200, RSI14 needs 15 (14 changes), the MACD line 26 and its signal and histogram 34.
 *
 * <p>Rule: BR-12; SRS 3.3.2; TECHNICAL_DESIGN 5.4 and 7.2.
 *
 * @param openTime the open time of the latest candle the values include
 * @param candles how many consecutive closed candles the values were computed from
 * @param sma20 the simple moving average of the last 20 closes
 * @param ema20 the exponential moving average of the closes, period 20
 * @param ema50 the exponential moving average of the closes, period 50
 * @param ema200 the exponential moving average of the closes, period 200
 * @param rsi14 the relative strength index, period 14, Wilder smoothing
 * @param macdLine EMA12 − EMA26 of the closes
 * @param macdSignal the EMA9 of the MACD line
 * @param macdHistogram the MACD line − its signal
 * @param bbUpper the upper Bollinger band, SMA20 + 2σ
 * @param bbMiddle the middle Bollinger band, SMA20
 * @param bbLower the lower Bollinger band, SMA20 − 2σ
 * @param volumeSma20 the simple moving average of the last 20 base-asset volumes
 */
public record IndicatorSnapshot(
        Instant openTime,
        int candles,
        Double sma20,
        Double ema20,
        Double ema50,
        Double ema200,
        Double rsi14,
        Double macdLine,
        Double macdSignal,
        Double macdHistogram,
        Double bbUpper,
        Double bbMiddle,
        Double bbLower,
        Double volumeSma20) {

    /** The number of consecutive candles after which every indicator has a value: EMA200's. */
    public static final int FULL_WARM_UP = 200;

    /** Whether every indicator has a value; until then some are {@code null}. */
    public boolean warmedUp() {
        return ema200 != null;
    }
}
