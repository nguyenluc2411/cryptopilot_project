package com.cryptopilot.paper.model.enums;

import java.math.BigDecimal;

/**
 * Which way the last price must move for a stop or a trailing stop to trigger (TR-09, TR-11). Fixed when the order is
 * placed, from where the price stood, so a replay triggers it the same way.
 *
 * <p>Rule: TR-09, TR-11.
 */
public enum TriggerCondition {

    /** Triggers when the last price trades at or above the stop. */
    AT_OR_ABOVE,

    /** Triggers when the last price trades at or below the stop. */
    AT_OR_BELOW;

    /** Whether a trade at {@code price} meets this condition for {@code stop}. */
    public boolean isMet(BigDecimal price, BigDecimal stop) {
        int side = price.compareTo(stop);
        return this == AT_OR_BELOW ? side <= 0 : side >= 0;
    }
}
