package com.cryptopilot.paper.service;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.dto.response.PnlAnalysisResponse;
import java.util.UUID;

/**
 * The PnL analysis of a paper account (TR-28, plan section 8): today's PnL, the PnL of each day, its running total and
 * deepest fall, of one wallet or both, and the record of the Futures positions closed.
 *
 * <p>Rule: TR-28.
 */
public interface PaperPnlAnalysisService {

    /**
     * @param market {@code SPOT} or {@code FUTURES} for that wallet, {@code null} for both
     * @param days the period ending today: 7, 30 or 90; 30 when absent
     */
    PnlAnalysisResponse analysis(UUID userId, MarketType market, Integer days);
}
