package com.cryptopilot.trading.model;

import java.util.List;

/**
 * A plan that cannot be sized, or whose liquidation price cannot be estimated, with every value that prevented it.
 *
 * <p>Rule: BR-21, BR-22, BR-23, BR-26, BR-30; SRS 3.5.1.
 *
 * @param violations at least one
 */
public record RiskInputRejected(List<InputViolation> violations) implements RiskOutcome, LiquidationOutcome {

    public RiskInputRejected {
        violations = List.copyOf(violations);
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("a rejection names at least one violation");
        }
    }
}
