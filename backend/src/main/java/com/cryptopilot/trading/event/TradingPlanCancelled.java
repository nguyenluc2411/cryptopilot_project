package com.cryptopilot.trading.event;

import com.cryptopilot.market.model.enums.MarketType;
import java.util.UUID;

/**
 * A Trader has cancelled a plan. Published inside the cancelling transaction; the matching engine reads it after the
 * commit.
 *
 * <p>Rule: UC-19, NSF-07.
 *
 * @param planId the plan
 * @param market the market, which with the pair names the matching partition
 * @param pairId the pair
 */
public record TradingPlanCancelled(UUID planId, MarketType market, UUID pairId) {}
