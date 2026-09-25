package com.cryptopilot.market.model;

/**
 * The result of offering a closed candle to the indicators of its series.
 *
 * <p>Rule: BR-12; NSF-05; TECHNICAL_DESIGN 7.2.
 *
 * @param outcome what happened to the candle
 * @param snapshot the indicators of the series afterwards, or {@code null} when the series has no stored candle
 */
public record IndicatorUpdate(IndicatorOutcome outcome, IndicatorSnapshot snapshot) {}
