package com.cryptopilot.trading.model;

import java.math.BigDecimal;

/**
 * The margin and estimated liquidation price of a Futures plan. Amounts keep full precision (CR-03); only the
 * liquidation price is rounded, to the tick and towards the position (TECHNICAL_DESIGN 5.4). Turning the two flags
 * into BLOCKING warnings is the warning evaluator's.
 *
 * <p>Rule: BR-26, BR-27, BR-28; CR-03; TECHNICAL_DESIGN 5.4, 7.5 and 7.6; A-01.
 *
 * @param bracket the bracket that contains the notional, with its maintenance amount filled in
 * @param notional quantity × entry price
 * @param initialMargin notional / leverage, the wallet balance of the isolated position
 * @param maintenanceMargin notional × MMR − maintenance amount
 * @param liquidationPrice the estimate, LONG rounded up and SHORT down to the tick; 0 for a LONG the price cannot
 *     liquidate
 * @param marginExceedsCapital the initial margin above the capital (BR-26, BLOCKING INSUFFICIENT_CAPITAL)
 * @param stopBeyondLiquidation LONG stop loss at or below, SHORT at or above the liquidation price (BR-28, BLOCKING
 *     SL_BEYOND_LIQUIDATION)
 */
public record LiquidationEstimate(
        LeverageBracket bracket,
        BigDecimal notional,
        BigDecimal initialMargin,
        BigDecimal maintenanceMargin,
        BigDecimal liquidationPrice,
        boolean marginExceedsCapital,
        boolean stopBeyondLiquidation)
        implements LiquidationOutcome {}
