package com.cryptopilot.paper.model.enums;

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
    AT_OR_BELOW
}
