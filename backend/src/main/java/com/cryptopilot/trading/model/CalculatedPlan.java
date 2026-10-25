package com.cryptopilot.trading.model;

import java.util.List;
import java.util.Objects;

/**
 * A plan run through the sizing, the liquidation estimate and the warning rules: what the aggregate saves.
 *
 * <p>Rule: BR-23 to BR-29, BR-36; D-112 R6.
 *
 * @param calculation the inputs and results
 * @param warnings the warnings, most severe first
 * @param entry how the entry is expected to fill: for MARKET its reference price, which the calculation's entry price
 *     moves by the slippage
 */
public record CalculatedPlan(PlanCalculation calculation, List<PlanWarning> warnings, EntryExpectation entry) {

    public CalculatedPlan {
        Objects.requireNonNull(calculation, "calculation");
        Objects.requireNonNull(entry, "entry");
        warnings = List.copyOf(warnings);
    }
}
