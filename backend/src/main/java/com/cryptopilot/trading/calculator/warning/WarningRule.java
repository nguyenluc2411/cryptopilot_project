package com.cryptopilot.trading.calculator.warning;

import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanWarning;
import java.util.Optional;

/**
 * One warning of a trading plan: a pure check of the calculated plan that raises at most one warning of its type.
 *
 * <p>Rule: BR-25, BR-26, BR-28, BR-29; TECHNICAL_DESIGN 7.5.
 * <p>Reference: Evans, E., &amp; Fowler, M. (1997). <i>Specifications</i> (the specification pattern: one business
 * rule per object).
 */
public interface WarningRule {

    /** The warning this rule raises for the plan, or empty when the plan passes it. */
    Optional<PlanWarning> evaluate(PlanCalculation plan);
}
