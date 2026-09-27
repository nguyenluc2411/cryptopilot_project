package com.cryptopilot.market.service;

import com.cryptopilot.market.dto.response.AnalysisResponse;
import java.util.UUID;

/**
 * The analysis of a pair for SCR-10 and SCR-11 (UC-10, UC-11); implemented by
 * {@link com.cryptopilot.market.service.impl.AnalysisServiceImpl}.
 *
 * <p>Rule: UC-10, UC-11, BR-12, BR-13, BR-14; TECHNICAL_DESIGN 7.4; D-48, D-53.
 */
public interface AnalysisService {

    /**
     * The latest stored analysis of a series and the setup score of the caller's preset, computed now.
     *
     * @param market {@code spot} or {@code futures}, in any case
     * @param symbol the symbol, in any case
     * @param timeframe {@code 15m}, {@code 1h}, {@code 4h} or {@code 1d} (BR-08); {@code null} for 1h (SRS 3.3.2)
     * @param userId the signed-in caller, whose profile selects the preset; {@code null} reads the default preset
     * @throws com.cryptopilot.common.exception.BusinessException {@code VALIDATION_FAILED} (MSG01) for an unknown
     *     market or timeframe
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when no pair with that symbol is enabled on
     *     that market (BR-07)
     */
    AnalysisResponse analysis(String market, String symbol, String timeframe, UUID userId);
}
