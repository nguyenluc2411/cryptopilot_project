package com.cryptopilot.watchlist.model.enums;

/**
 * How an alert compares its value with the threshold. Stored by name in {@code alert.condition_operator}.
 *
 * <p>Rule: BR-20.
 */
public enum ConditionOperator {

    /** Met when the previous value is below the threshold and the current one is at or above it. */
    CROSS_ABOVE,

    /** Met when the previous value is above the threshold and the current one is at or below it. */
    CROSS_BELOW,

    /** Met whenever the current value is above the threshold. */
    GREATER_THAN,

    /** Met whenever the current value is below the threshold. */
    LESS_THAN;

    /** Whether the condition needs the previous value, i.e. is a cross. */
    public boolean isCross() {
        return this == CROSS_ABOVE || this == CROSS_BELOW;
    }
}
