package com.cryptopilot.market;

import java.math.BigDecimal;

/**
 * One leverage bracket of a Futures pair, as {@code leverage_bracket} stores it: the notional range it covers, the
 * highest leverage allowed in it and its maintenance margin rate and amount.
 *
 * <p>Rule: BR-26, BR-27; UC-46.
 *
 * @param bracketNo the bracket's number, 1 for the smallest notionals
 * @param notionalFloor the lowest notional of the bracket, inclusive
 * @param notionalCap the highest notional of the bracket, exclusive
 * @param maxLeverage the highest leverage allowed in the bracket
 * @param maintenanceMarginRate the maintenance margin rate
 * @param maintenanceAmount the maintenance amount
 */
public record LeverageTier(
        int bracketNo,
        BigDecimal notionalFloor,
        BigDecimal notionalCap,
        int maxLeverage,
        BigDecimal maintenanceMarginRate,
        BigDecimal maintenanceAmount) {}
