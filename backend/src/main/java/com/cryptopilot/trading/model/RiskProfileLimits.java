package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The money-management parameters of the Trader's risk profile, passed to the calculation by its caller; the
 * calculation never reads a profile and holds none of its values.
 *
 * <p>Rule: BR-66, BR-29; D-53.
 *
 * @param riskPerTradePercent the risk per trade, in percent of capital
 * @param maxLeverage the maximum futures leverage
 * @param maxTotalOpenRiskPercent the maximum risk of all ACTIVE plans and open positions together, in percent of
 *     capital
 */
public record RiskProfileLimits(BigDecimal riskPerTradePercent, int maxLeverage, BigDecimal maxTotalOpenRiskPercent) {

    public RiskProfileLimits {
        requirePositive(riskPerTradePercent, "riskPerTradePercent");
        requirePositive(maxTotalOpenRiskPercent, "maxTotalOpenRiskPercent");
        if (maxLeverage < 1) {
            throw new IllegalArgumentException("maxLeverage must be at least 1, was " + maxLeverage);
        }
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (Objects.requireNonNull(value, name + " must not be null").signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
    }
}
