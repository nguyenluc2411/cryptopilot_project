package com.cryptopilot.market.model;

import com.cryptopilot.market.model.enums.IndicatorOutcome;

/**
 * The result of offering a closed candle to the indicators of its series.
 *
 * <p>Rule: BR-12; NSF-05; TECHNICAL_DESIGN 7.2.
 *
 * @param outcome what happened to the candle
 * @param snapshot the indicators of the series afterwards, or {@code null} when the series has no stored candle
 * @param previousMacdHistogram the MACD histogram of the candle before the snapshot's, from the series itself, or
 *     {@code null} when the series has none for it
 */
public record IndicatorUpdate(IndicatorOutcome outcome, IndicatorSnapshot snapshot, Double previousMacdHistogram) {}
