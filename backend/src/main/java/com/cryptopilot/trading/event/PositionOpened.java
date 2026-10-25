package com.cryptopilot.trading.event;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A plan filled at its activation (MARKET, or a LIMIT the last price already reached) and its position is OPEN in the
 * journal. Published inside the activating transaction; after the commit the matching engine adds the position to its
 * exit books. A fill the engine decides opens the position in the engine directly and publishes nothing.
 *
 * <p>Rule: BR-33, BR-34, BR-35; NSF-07; D-119.
 *
 * @param positionId the journal record
 * @param planId the plan
 * @param userId the owner
 * @param market SPOT or FUTURES
 * @param pairId the pair
 * @param direction LONG or SHORT
 * @param entryPrice the stored fill price
 * @param entryTime the activation instant (D-78)
 * @param stopLoss the stop loss
 * @param takeProfit the take profit
 * @param liquidationPrice the liquidation price of a Futures position, or {@code null}
 * @param version the journal row's version
 */
public record PositionOpened(
        UUID positionId,
        UUID planId,
        UUID userId,
        MarketType market,
        UUID pairId,
        Direction direction,
        BigDecimal entryPrice,
        Instant entryTime,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal liquidationPrice,
        long version) {}
