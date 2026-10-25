package com.cryptopilot.trading;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The position a filled plan opens, as the journal records it (BR-34). Every value is the plan's or the fill's; the
 * journal stores them and decides nothing.
 *
 * <p>Rule: BR-34, BR-36; D-78, D-97, D-119.
 *
 * @param planId the plan that filled
 * @param userId its owner
 * @param pairId the pair
 * @param market SPOT or FUTURES
 * @param direction LONG or SHORT
 * @param leverage the leverage; {@code null} on Spot
 * @param entryPrice the actual fill price
 * @param entryTime when the fill counts: the open of the candle that reached the entry, or the activation instant
 *     (D-78)
 * @param quantity the plan snapshot's quantity
 * @param entryLiquidity the plan's {@code fill_liquidity}
 * @param entryFee the fee of the entry fill by its liquidity role, at the stored scale
 * @param stopLoss the stop loss the position starts with
 * @param takeProfit the take profit the position starts with
 * @param liquidationPrice the liquidation price of a Futures position at its fill price, or {@code null} for Spot and
 *     for a position no price can liquidate
 */
public record SimulatedTradeOpening(
        UUID planId,
        UUID userId,
        UUID pairId,
        MarketType market,
        Direction direction,
        Integer leverage,
        BigDecimal entryPrice,
        Instant entryTime,
        BigDecimal quantity,
        LiquidityRole entryLiquidity,
        BigDecimal entryFee,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal liquidationPrice) {

    public SimulatedTradeOpening {
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
        Objects.requireNonNull(stopLoss, "stopLoss");
        Objects.requireNonNull(takeProfit, "takeProfit");
    }
}
