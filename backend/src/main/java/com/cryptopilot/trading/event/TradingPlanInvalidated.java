package com.cryptopilot.trading.event;

import com.cryptopilot.trading.model.enums.InvalidationReason;
import java.time.Instant;
import java.util.UUID;

/**
 * The matching engine has invalidated a waiting plan: an invalidation level was reached before its entry. Published
 * inside the transaction whose compare-and-set moved the plan to INVALIDATED, and only then; a listener reads it after
 * the commit ({@code @TransactionalEventListener}), so a plan filled, cancelled or expired first publishes nothing.
 * The Trader's notice (MSG54) is T-046.
 *
 * <p>Rule: BR-68, NSF-07; TECHNICAL_DESIGN 7.7; D-111 (M3).
 *
 * @param planId the plan
 * @param userId the plan's owner
 * @param reason which level was reached
 * @param invalidatedAt when the plan counts as invalidated: the open time of the candle that reached the level (D-78)
 */
public record TradingPlanInvalidated(UUID planId, UUID userId, InvalidationReason reason, Instant invalidatedAt) {}
