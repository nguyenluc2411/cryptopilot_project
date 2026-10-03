package com.cryptopilot.trading.event;

import java.util.UUID;

/**
 * A Trader has cancelled a plan. Published inside the cancelling transaction; the matching engine reads it after the
 * commit.
 *
 * <p>Rule: UC-19, NSF-07.
 *
 * @param planId the plan
 */
public record TradingPlanCancelled(UUID planId) {}
