package com.cryptopilot.trading.event;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.InvalidationReason;
import java.time.Instant;
import java.util.UUID;

/**
 * The matching engine has invalidated a waiting plan: an invalidation level was reached before its entry. Published
 * inside the transaction whose compare-and-set moved the plan to INVALIDATED, and only then; a listener reads it after
 * the commit ({@code @TransactionalEventListener}), so a plan filled, cancelled or expired first publishes nothing.
 * The Trader is told with MSG54 and the reason (T-046); the worker has already taken the plan out of its books.
 *
 * <p>Rule: BR-68, NSF-07; TECHNICAL_DESIGN 7.7; D-111 (M3).
 *
 * @param planId the plan
 * @param userId the plan's owner
 * @param market SPOT or FUTURES
 * @param direction LONG or SHORT, named in MSG54
 * @param symbol the pair's symbol, or {@code null} when it could not be read
 * @param reason which level was reached
 * @param invalidatedAt when the plan counts as invalidated: the open time of the candle that reached the level (D-78)
 */
public record TradingPlanInvalidated(
        UUID planId,
        UUID userId,
        MarketType market,
        Direction direction,
        String symbol,
        InvalidationReason reason,
        Instant invalidatedAt) {}
