package com.cryptopilot.trading.model;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.trading.Direction;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * What the margin and liquidation price of a Futures plan are calculated from: the sized quantity of the risk
 * calculation, the plan's prices, leverage and capital, the pair's tick size and its leverage brackets. The brackets
 * are passed in; the calculation never reads them.
 *
 * <p>Rule: BR-26, BR-27, BR-28; TECHNICAL_DESIGN 7.5 and 7.6.
 *
 * @param direction LONG or SHORT
 * @param entryPrice the entry price
 * @param stopLoss the stop loss, compared with the liquidation price (BR-28)
 * @param quantity the quantity of the risk calculation, above 0
 * @param leverage the leverage, at least 1
 * @param capital the capital committed to the plan, in USDT
 * @param tickSize the pair's tick size, which the liquidation price is rounded to
 * @param brackets the pair's leverage brackets, at least one
 */
public record LiquidationInput(
        Direction direction,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal quantity,
        int leverage,
        BigDecimal capital,
        BigDecimal tickSize,
        List<LeverageBracket> brackets) {

    public LiquidationInput {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(stopLoss, "stopLoss");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(capital, "capital");
        Objects.requireNonNull(tickSize, "tickSize");
        brackets = List.copyOf(brackets);
        if (brackets.isEmpty()) {
            throw new IllegalArgumentException("a futures pair has at least one leverage bracket");
        }
        if (quantity.signum() <= 0 || entryPrice.signum() <= 0 || leverage < 1) {
            throw new IllegalArgumentException("quantity, entry price and leverage come from a sized plan");
        }
    }

    /** The input of a Futures plan that the risk calculation has sized. */
    public static LiquidationInput of(RiskInput plan, RiskCalculation sized, List<LeverageBracket> brackets) {
        if (plan.market() != MarketType.FUTURES) {
            throw new IllegalArgumentException("a Spot plan has no liquidation price (BR-21)");
        }
        return new LiquidationInput(
                plan.direction(),
                plan.entryPrice(),
                plan.stopLoss(),
                sized.quantity(),
                plan.leverage(),
                plan.capital(),
                plan.filters().tickSize(),
                brackets);
    }
}
