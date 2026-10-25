package com.cryptopilot.trading.event;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import java.time.Instant;
import java.util.UUID;

/**
 * An ACTIVE plan reached its expiry before its entry filled and is EXPIRED (NSF-09). Published inside the transaction
 * whose compare-and-set moved the plan, and only then; after the commit the worker takes the plan out of its books and
 * the Trader is told with MSG53.
 *
 * <p>Rule: NSF-09, BR-32, BR-62; D-79.
 *
 * @param planId the plan
 * @param userId the owner
 * @param market SPOT or FUTURES
 * @param pairId the pair
 * @param direction LONG or SHORT, named in MSG53
 * @param symbol the pair's symbol, or {@code null} when it could not be read
 * @param expiredAt when the plan was expired
 */
public record TradingPlanExpired(
        UUID planId,
        UUID userId,
        MarketType market,
        UUID pairId,
        Direction direction,
        String symbol,
        Instant expiredAt) {}
