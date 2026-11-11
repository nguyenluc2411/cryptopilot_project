package com.cryptopilot.market.dto.response;

import com.cryptopilot.market.model.enums.CapReason;
import com.cryptopilot.market.model.enums.DominantSide;
import com.cryptopilot.market.model.enums.ScoredSide;
import com.cryptopilot.market.model.enums.SetupStyle;
import java.time.Instant;

/**
 * A Futures setup score, what it was read from, and the winning side of its trend beside it. The side is information
 * about the score and never part of it; it is computed when read and not stored.
 *
 * <p>Rule: BR-13; SRS 3.3.2; TECHNICAL_DESIGN 7.4; D-53.
 *
 * @param score 0–100, or {@code null} for insufficient data
 * @param preset the style preset the components were weighted under
 * @param presetVersion the version of that preset
 * @param timeframe the preset's timeframe, which the components were read on
 * @param openTime the open time of the candle the components belong to, or {@code null} when none is stored
 * @param components the components the score was weighted from, or {@code null} when the plan locks them
 * @param dominantSide LONG, SHORT or NEUTRAL, or {@code null} when an EMA or the candle's close is missing
 * @param capReason {@code LOW_ROOM} when the score was capped for too little room to the next level, else
 *     {@code null}
 * @param scoredSide the side the score is computed for (BR-51: information about the score, not a trade
 *     recommendation); {@code null} on rows scored before it was stored and when no candle is analysed
 */
public record FuturesSetupScoreResponse(
        Integer score,
        SetupStyle preset,
        String presetVersion,
        String timeframe,
        Instant openTime,
        FuturesComponentsResponse components,
        DominantSide dominantSide,
        CapReason capReason,
        ScoredSide scoredSide)
        implements SetupScoreResponse {}
