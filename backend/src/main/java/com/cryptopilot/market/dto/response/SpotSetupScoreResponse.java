package com.cryptopilot.market.dto.response;

import com.cryptopilot.market.model.enums.SetupStyle;
import java.time.Instant;

/**
 * A Spot setup score and what it was read from. The score is {@code null} when a component it weighs is missing,
 * never a partial sum.
 *
 * <p>Rule: BR-13; SRS 3.3.2; TECHNICAL_DESIGN 7.4; D-53.
 *
 * @param score 0–100, or {@code null} for insufficient data
 * @param preset the style preset the components were weighted under
 * @param presetVersion the version of that preset
 * @param timeframe the preset's timeframe, which the components were read on
 * @param openTime the open time of the candle the components belong to, or {@code null} when none is stored
 * @param components the components the score was weighted from
 */
public record SpotSetupScoreResponse(
        Integer score,
        SetupStyle preset,
        String presetVersion,
        String timeframe,
        Instant openTime,
        SpotComponentsResponse components)
        implements SetupScoreResponse {}
