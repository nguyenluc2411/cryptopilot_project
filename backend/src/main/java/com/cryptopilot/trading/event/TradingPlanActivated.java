package com.cryptopilot.trading.event;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A plan has become ACTIVE and waits for its entry: a LIMIT plan whose price was not reached at activation, or a STOP
 * plan whose stop was not triggered (BR-33). Published inside the activating transaction; the matching engine reads it
 * after the commit and tracks the entry with its invalidation levels (BR-68).
 *
 * <p>Rule: UC-18, NSF-07, BR-68; TECHNICAL_DESIGN 7.7.
 *
 * @param planId the plan
 * @param userId the plan's owner
 * @param market the market
 * @param pairId the pair
 * @param direction LONG or SHORT
 * @param entryType LIMIT or STOP (a MARKET entry, or a LIMIT the last price reached at activation, fills at once and
 *     is never published here)
 * @param entryPrice the entry price of the stored snapshot; the stop price of a STOP
 * @param stopLoss the stop loss of the stored snapshot
 * @param takeProfit the take profit of the stored snapshot
 * @param invalidationPrice the plan's invalidation price, or {@code null} for none
 * @param activatedAt when the plan became ACTIVE
 */
public record TradingPlanActivated(
        UUID planId,
        UUID userId,
        MarketType market,
        UUID pairId,
        Direction direction,
        EntryType entryType,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal invalidationPrice,
        Instant activatedAt) {}
