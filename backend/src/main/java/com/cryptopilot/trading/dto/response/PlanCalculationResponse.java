package com.cryptopilot.trading.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * The live risk panel of SCR-17: the calculation of the values entered, nothing saved.
 *
 * <p>Rule: BR-23 to BR-29; SRS 3.5.1.
 *
 * @param entryPrice the entry price: the one entered, rounded to the tick, or the current last price (the reference
 *     price) for MARKET
 * @param expectedFillPrice MARKET only: the reference price moved against the Trader by {@code SLIPPAGE_BPS}, rounded
 *     to the tick against them; the price the quantity is sized on and the activation fills at (D-112 R6). Equal to
 *     {@code entryPrice} at 0 bps; {@code null} for LIMIT and STOP
 * @param capital the capital calculated with: the profile's default capital, not an input of the plan (D-121)
 * @param riskPercent the risk % calculated with
 * @param snapshot the calculated values
 * @param warnings the warnings, most severe first
 * @param blocksActivation whether a warning would refuse activation (MSG18)
 */
public record PlanCalculationResponse(
        BigDecimal entryPrice,
        BigDecimal expectedFillPrice,
        BigDecimal capital,
        BigDecimal riskPercent,
        PlanSnapshotResponse snapshot,
        List<PlanWarningResponse> warnings,
        boolean blocksActivation) {}
