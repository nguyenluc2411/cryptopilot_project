package com.cryptopilot.ai.model;

import com.cryptopilot.market.model.enums.MarketType;
import java.util.UUID;

/**
 * What the Trader chose in the context selector of SCR-29 (SRS 3.9.1): a pair, its market and timeframe, optionally
 * one of the Trader's plans, and on PREMIUM whether to add the performance summary. Every part is optional; a
 * question without any is a general one.
 *
 * @param pairId the pair, or {@code null} for the plan's pair or none
 * @param market the market, or {@code null} for the plan's market or Spot
 * @param timeframe the timeframe, or {@code null} for the plan's analysis timeframe or 1h
 * @param planId one of the Trader's plans, or {@code null}
 * @param includePerformance whether to add the performance summary (PREMIUM, BR-51)
 */
public record AiContextSelection(
        UUID pairId, MarketType market, String timeframe, UUID planId, boolean includePerformance) {

    public static final AiContextSelection NONE = new AiContextSelection(null, null, null, null, false);
}
