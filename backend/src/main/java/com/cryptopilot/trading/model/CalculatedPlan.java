package com.cryptopilot.trading.model;

import java.util.List;
import java.util.Objects;

/**
 * A plan run through the sizing, the liquidation estimate and the warning rules: what the aggregate saves.
 *
 * <p>Rule: BR-23 to BR-29.
 *
 * @param calculation the inputs and results
 * @param warnings the warnings, most severe first
 */
public record CalculatedPlan(PlanCalculation calculation, List<PlanWarning> warnings) {

    public CalculatedPlan {
        Objects.requireNonNull(calculation, "calculation");
        warnings = List.copyOf(warnings);
    }
}
