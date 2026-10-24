package com.cryptopilot.market.service;

import com.cryptopilot.market.SetupScoreReading;
import com.cryptopilot.market.dto.response.AnalysisResponse;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.SetupStyle;
import java.time.Instant;
import java.util.UUID;

/**
 * The analysis of a pair for SCR-10 and SCR-11 (UC-10, UC-11); implemented by
 * {@link com.cryptopilot.market.service.impl.AnalysisServiceImpl}.
 *
 * <p>Rule: UC-10, UC-11, BR-12, BR-13, BR-14, BR-62, BR-70; TECHNICAL_DESIGN 7.4; D-48, D-53, D-60, D-112.
 */
public interface AnalysisService {

    /**
     * The latest stored analysis of a series and the setup score of the caller's preset, computed now. The component
     * scores are withheld and marked locked unless the caller's plan includes {@code SCORE_COMPONENTS}.
     *
     * @param market {@code spot} or {@code futures}, in any case
     * @param symbol the symbol, in any case
     * @param timeframe {@code 15m}, {@code 1h}, {@code 4h} or {@code 1d} (BR-08); {@code null} for 1h (SRS 3.3.2)
     * @param userId the signed-in caller, whose profile selects the preset and whose plan the gates read; {@code null}
     *     reads the default preset with no plan
     * @throws com.cryptopilot.common.exception.BusinessException {@code VALIDATION_FAILED} (MSG01) for an unknown
     *     market or timeframe; on Futures {@code PLAN_FEATURE_NOT_INCLUDED} (MSG29) for a plan without
     *     {@code FUTURES_ANALYSIS}, or {@code AUTHENTICATION_REQUIRED} (MSG44) without a caller
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when no pair with that symbol is enabled on
     *     that market (BR-07)
     */
    AnalysisResponse analysis(String market, String symbol, String timeframe, UUID userId);

    /**
     * The setup score of the pair's latest candle of {@code timeframe} closed at {@code at} under a preset, as
     * {@link com.cryptopilot.market.MarketApi#closedCandleSetupScore} describes it.
     *
     * @param style the preset, or {@code null} for the default one
     * @param presetVersion the preset version, or {@code null} for the configured one
     * @param at the instant of the read, not after now
     */
    SetupScoreReading closedCandleSetupScore(
            UUID pairId, MarketType market, String timeframe, SetupStyle style, String presetVersion, Instant at);

    /**
     * The timeframe a preset reads its setup score on, as {@link com.cryptopilot.market.MarketApi#presetTimeframe}
     * describes it.
     *
     * @param style the preset, or {@code null} for the default one
     */
    String presetTimeframe(SetupStyle style);
}
