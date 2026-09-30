package com.cryptopilot.trading.calculator.warning;

import com.cryptopilot.trading.model.LiquidationEstimate;
import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.enums.WarningType;
import java.util.Optional;

/**
 * BLOCKING SL_BEYOND_LIQUIDATION when a Futures position would be liquidated before its stop loss is reached: a LONG
 * stop at or below the liquidation price, a SHORT stop at or above it. The comparison is the liquidation
 * calculator's (T-036), on the price it rounded towards the position; a LONG it cannot liquidate reports 0 and never
 * raises this warning.
 *
 * <p>Rule: BR-28; TECHNICAL_DESIGN 7.5 and 7.6; A-01.
 * <p>Reference: Hull, J. C. (2022). <i>Options, Futures, and Other Derivatives</i> (11th ed.). Pearson, ch. 2
 * (maintenance margin and the margin call).
 */
public final class StopBeyondLiquidationRule implements WarningRule {

    @Override
    public Optional<PlanWarning> evaluate(PlanCalculation plan) {
        LiquidationEstimate estimate = plan.liquidation();
        if (estimate == null || !estimate.stopBeyondLiquidation()) {
            return Optional.empty();
        }
        return Optional.of(new PlanWarning(
                WarningType.SL_BEYOND_LIQUIDATION,
                "the stop loss " + plan.plan().stopLoss() + " is at or beyond the estimated liquidation price "
                        + estimate.liquidationPrice()));
    }
}
