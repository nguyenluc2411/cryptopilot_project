package com.cryptopilot.market.dto.response;

import com.cryptopilot.market.model.enums.CapReason;
import com.cryptopilot.market.model.enums.ScoredSide;
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
 * @param components the components the score was weighted from, or {@code null} when the plan locks them
 * @param capReason {@code LOW_ROOM} when the score was capped for too little room to the next level, else
 *     {@code null}
 * @param scoredSide the side the score is computed for (BR-51: information about the score, not a trade
 *     recommendation); {@code null} on rows scored before it was stored and when no candle is analysed
 */
public record SpotSetupScoreResponse(
        Integer score,
        SetupStyle preset,
        String presetVersion,
        String timeframe,
        Instant openTime,
        SpotComponentsResponse components,
        CapReason capReason,
        ScoredSide scoredSide)
        implements SetupScoreResponse {}
