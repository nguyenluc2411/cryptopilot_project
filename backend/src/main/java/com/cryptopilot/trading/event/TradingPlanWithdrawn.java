package com.cryptopilot.trading.event;

import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.UUID;

/**
 * An ACTIVE plan was cancelled because an administrator took its pair's market out of service (BR-59), not by its
 * owner. Published inside the cancelling transaction beside {@link TradingPlanCancelled}, which the matching engine
 * reads; this one names the owner, for the notification BR-59 asks for.
 *
 * <p>Rule: BR-59; UC-45.
 *
 * @param planId the plan
 * @param userId its owner, to be told
 * @param symbol the pair's symbol, e.g. {@code BTCUSDT}
 * @param market the market taken out of service
 * @param cancelledAt when
 */
public record TradingPlanWithdrawn(UUID planId, UUID userId, String symbol, MarketType market, Instant cancelledAt) {}
