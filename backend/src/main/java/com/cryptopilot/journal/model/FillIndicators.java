package com.cryptopilot.journal.model;

import com.cryptopilot.market.ClosedCandleIndicators;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * The indicator values stored with a fill in {@code fill_indicators}, the latest closed candle of the plan's analysis
 * timeframe at the fill. Decimals are kept as JSON strings, so they read back exactly as they were written.
 *
 * <p>Rule: BR-70; D-111 (M4), D-112.
 *
 * @param timeframe the timeframe the candle is of
 * @param openTime the open time of that candle
 * @param rsi14 RSI14, or {@code null} during warm-up
 * @param ema20 EMA20, or {@code null} during warm-up
 * @param ema50 EMA50, or {@code null} during warm-up
 * @param ema200 EMA200, or {@code null} during warm-up
 * @param macdLine the MACD line, or {@code null} during warm-up
 * @param macdSignal the MACD signal line, or {@code null} during warm-up
 * @param macdHistogram the MACD histogram, or {@code null} during warm-up
 */
public record FillIndicators(
        String timeframe,
        Instant openTime,
        BigDecimal rsi14,
        BigDecimal ema20,
        BigDecimal ema50,
        BigDecimal ema200,
        BigDecimal macdLine,
        BigDecimal macdSignal,
        BigDecimal macdHistogram) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public FillIndicators {
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(openTime, "openTime");
    }

    /** The values the market module read at the fill. */
    public static FillIndicators of(ClosedCandleIndicators read) {
        return new FillIndicators(
                read.timeframe(),
                read.openTime(),
                read.rsi14(),
                read.ema20(),
                read.ema50(),
                read.ema200(),
                read.macdLine(),
                read.macdSignal(),
                read.macdHistogram());
    }

    /** The JSON the column stores. */
    public String toJson() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("timeframe", timeframe);
        values.put("openTime", openTime.toString());
        values.put("rsi14", text(rsi14));
        values.put("ema20", text(ema20));
        values.put("ema50", text(ema50));
        values.put("ema200", text(ema200));
        values.put("macdLine", text(macdLine));
        values.put("macdSignal", text(macdSignal));
        values.put("macdHistogram", text(macdHistogram));
        return JSON.writeValueAsString(values);
    }

    /** The values of a stored column, or {@code null} for none. */
    public static FillIndicators fromJson(String json) {
        if (json == null) {
            return null;
        }
        Map<?, ?> values = JSON.readValue(json, Map.class);
        return new FillIndicators(
                (String) values.get("timeframe"),
                Instant.parse((String) values.get("openTime")),
                decimal(values.get("rsi14")),
                decimal(values.get("ema20")),
                decimal(values.get("ema50")),
                decimal(values.get("ema200")),
                decimal(values.get("macdLine")),
                decimal(values.get("macdSignal")),
                decimal(values.get("macdHistogram")));
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }
}
