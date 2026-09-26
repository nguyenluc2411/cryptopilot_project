package com.cryptopilot.market.model;

import com.cryptopilot.market.SetupStyle;

/**
 * A setup score read under one style preset, with the components it was weighted from.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4; D-53.
 *
 * @param score the weighted sum 0–100 rounded to an integer, or {@code null} when a component it needs is missing
 * @param components the component scores it was computed from
 * @param style the preset
 * @param presetVersion the preset version
 * @param timeframe the timeframe the components were read on
 * @param dominantSide the winning side of the trend on Futures, beside the score and not part of it; {@code null} on
 *     Spot and when the trend could not be read
 */
public record SetupScore(
        Integer score,
        ComponentScores components,
        SetupStyle style,
        String presetVersion,
        String timeframe,
        DominantSide dominantSide) {}
