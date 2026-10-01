package com.cryptopilot.trading.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * The live risk panel of SCR-17: the calculation of the values entered, nothing saved.
 *
 * <p>Rule: BR-23 to BR-29; SRS 3.5.1.
 *
 * @param entryPrice the entry price calculated with: the one entered, rounded to the tick, or the current last price
 *     for MARKET
 * @param capital the capital calculated with
 * @param riskPercent the risk % calculated with
 * @param snapshot the calculated values
 * @param warnings the warnings, most severe first
 * @param blocksActivation whether a warning would refuse activation (MSG18)
 */
public record PlanCalculationResponse(
        BigDecimal entryPrice,
        BigDecimal capital,
        BigDecimal riskPercent,
        PlanSnapshotResponse snapshot,
        List<PlanWarningResponse> warnings,
        boolean blocksActivation) {}
