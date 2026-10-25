package com.cryptopilot.trading;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An OPEN simulated position as the journal holds it: what the matching engine tracks and what its close is computed
 * from. The current stop loss, take profit and liquidation price are the journal row's, versioned by {@code version}
 * (D-119).
 *
 * <p>Rule: BR-34, BR-35, BR-36; D-97, D-119.
 *
 * @param positionId the journal record
 * @param planId the plan that filled
 * @param userId its owner
 * @param pairId the pair
 * @param market SPOT or FUTURES
 * @param direction LONG or SHORT
 * @param entryPrice the actual fill price
 * @param entryTime when the fill counts (D-78)
 * @param quantity the quantity held
 * @param entryLiquidity the liquidity role of the entry fill
 * @param entryFee the stored fee of the entry fill
 * @param stopLoss the current stop loss
 * @param takeProfit the current take profit
 * @param liquidationPrice the liquidation price of a Futures position at its fill, or {@code null}
 * @param version the row's version, which a close compares and sets
 */
public record OpenTrade(
        UUID positionId,
        UUID planId,
        UUID userId,
        UUID pairId,
        MarketType market,
        Direction direction,
        BigDecimal entryPrice,
        Instant entryTime,
        BigDecimal quantity,
        LiquidityRole entryLiquidity,
        BigDecimal entryFee,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal liquidationPrice,
        long version) {

    public OpenTrade {
        Objects.requireNonNull(positionId, "positionId");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(entryTime, "entryTime");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(entryLiquidity, "entryLiquidity");
        Objects.requireNonNull(entryFee, "entryFee");
    }
}
