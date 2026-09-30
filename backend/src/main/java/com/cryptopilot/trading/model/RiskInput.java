package com.cryptopilot.trading.model;

import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.Direction;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * What the risk panel of a plan is calculated from: the plan's values as the Trader entered them, the pair's filters
 * and the Trader's risk profile. Values outside BR-21, BR-22 or BR-30 are not refused here but reported by the
 * calculation, so every field can show its message at once; only a missing value is a programming error.
 *
 * <p>Rule: BR-21, BR-22, BR-23, BR-30; D-53.
 *
 * @param market Spot or Futures
 * @param direction LONG or SHORT
 * @param entryPrice the entry price (the current last price for a MARKET entry)
 * @param stopLoss the stop loss
 * @param takeProfit the take profit
 * @param capital the capital committed to the plan, in USDT
 * @param riskPercent the risk % of capital
 * @param leverage the leverage; 1 on Spot
 * @param filters the pair's tick size, step size and minimum notional on this market
 * @param profile the Trader's risk profile parameters
 * @param otherOpenRisk the risk amount of the Trader's other ACTIVE plans and open positions, in USDT
 */
public record RiskInput(
        MarketType market,
        Direction direction,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal capital,
        BigDecimal riskPercent,
        int leverage,
        PairFilters filters,
        RiskProfileLimits profile,
        BigDecimal otherOpenRisk) {

    public RiskInput {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(stopLoss, "stopLoss");
        Objects.requireNonNull(takeProfit, "takeProfit");
        Objects.requireNonNull(capital, "capital");
        Objects.requireNonNull(riskPercent, "riskPercent");
        Objects.requireNonNull(filters, "filters");
        Objects.requireNonNull(profile, "profile");
        if (Objects.requireNonNull(otherOpenRisk, "otherOpenRisk").signum() < 0) {
            throw new IllegalArgumentException("otherOpenRisk must not be negative, was " + otherOpenRisk);
        }
    }
}
