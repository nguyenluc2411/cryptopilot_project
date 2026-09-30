package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One leverage bracket of a futures pair: the notional range it covers, the highest leverage it allows and the
 * maintenance margin it requires. A notional belongs to the bracket when {@code floor <= notional < cap}.
 *
 * <p>Rule: BR-26, BR-27; SCR-38; TECHNICAL_DESIGN 7.5 and 7.6.
 *
 * @param bracketNo the bracket number, 1 for the smallest notionals
 * @param notionalFloor the lowest notional of the bracket, inclusive, in USDT
 * @param notionalCap the notional where the next bracket starts, exclusive, in USDT
 * @param maxLeverage the highest leverage allowed in the bracket
 * @param maintenanceMarginRate the maintenance margin rate (MMR), from 0 inclusive to 1 exclusive
 * @param maintenanceAmount the maintenance amount {@code cum} of the bracket in USDT, or {@code null} when the source
 *     does not give it; the calculation then derives it from the brackets below
 */
public record LeverageBracket(
        int bracketNo,
        BigDecimal notionalFloor,
        BigDecimal notionalCap,
        int maxLeverage,
        BigDecimal maintenanceMarginRate,
        BigDecimal maintenanceAmount) {

    public LeverageBracket {
        Objects.requireNonNull(notionalFloor, "notionalFloor");
        Objects.requireNonNull(notionalCap, "notionalCap");
        Objects.requireNonNull(maintenanceMarginRate, "maintenanceMarginRate");
        if (notionalFloor.signum() < 0 || notionalCap.compareTo(notionalFloor) <= 0) {
            throw new IllegalArgumentException(
                    "bracket " + bracketNo + " needs 0 <= floor < cap, was " + notionalFloor + " / " + notionalCap);
        }
        if (maxLeverage < 1) {
            throw new IllegalArgumentException("bracket " + bracketNo + " allows at least 1x, was " + maxLeverage);
        }
        if (maintenanceMarginRate.signum() < 0 || maintenanceMarginRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(
                    "bracket " + bracketNo + " needs 0 <= MMR < 1, was " + maintenanceMarginRate);
        }
        if (maintenanceAmount != null && maintenanceAmount.signum() < 0) {
            throw new IllegalArgumentException(
                    "bracket " + bracketNo + " has a negative maintenance amount " + maintenanceAmount);
        }
    }

    /** Whether the notional falls in {@code [floor, cap)}. */
    public boolean contains(BigDecimal notional) {
        return notional.compareTo(notionalFloor) >= 0 && notional.compareTo(notionalCap) < 0;
    }

    /** The same bracket with the given maintenance amount. */
    public LeverageBracket withMaintenanceAmount(BigDecimal amount) {
        return new LeverageBracket(bracketNo, notionalFloor, notionalCap, maxLeverage, maintenanceMarginRate, amount);
    }
}
