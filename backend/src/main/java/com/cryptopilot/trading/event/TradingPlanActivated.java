package com.cryptopilot.trading.event;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A plan has become ACTIVE and waits for its entry: a LIMIT plan whose price was not reached at activation (BR-33). Published inside the activating transaction; the matching engine
 * reads it after the commit.
 *
 * <p>Rule: UC-18, NSF-07; TECHNICAL_DESIGN 7.7.
 *
 * @param planId the plan
 * @param market the market
 * @param pairId the pair
 * @param direction LONG or SHORT
 * @param entryType LIMIT or MARKET
 * @param entryPrice the entry price of the stored snapshot
 * @param activatedAt when the plan became ACTIVE
 */
public record TradingPlanActivated(
        UUID planId,
        MarketType market,
        UUID pairId,
        Direction direction,
        EntryType entryType,
        BigDecimal entryPrice,
        Instant activatedAt) {}
