package com.cryptopilot.paper.model.enums;

/**
 * The risk warnings the paper Futures order form shows before an order is placed (TR-26), each with its severity. The
 * names are the trading plan's warnings (BR-28, BR-29) where the rule is the same, so the client shows them alike;
 * {@code INSUFFICIENT_MARGIN} is the form's own. A warning never refuses an order: placement alone decides, and a
 * BLOCKING one says the order would be refused ({@code -2019}) or its stop loss could never act.
 *
 * <p>Rule: TR-26; BR-28, BR-29.
 */
public enum FuturesWarningType {

    /** The order costs more than the balance it is paid from, as placement would refuse it ({@code -2019}). */
    INSUFFICIENT_MARGIN(Severity.BLOCKING),

    /** The stop loss is at or beyond the estimated liquidation price: the position would be liquidated first (BR-28). */
    SL_BEYOND_LIQUIDATION(Severity.BLOCKING),

    /** The leverage is above the maximum of the Trader's risk profile (BR-29, BR-66). */
    HIGH_LEVERAGE(Severity.WARNING),

    /** The loss at the stop loss is a larger share of the wallet than the profile's risk per trade (BR-29, BR-66). */
    OVERSIZED_POSITION(Severity.WARNING),

    /** The reward to risk of the take profit and stop loss is below the configured minimum (BR-29). */
    LOW_RR(Severity.WARNING),

    /** The funding rate is at or above the configured size, on the side the position would be (BR-29). */
    HIGH_FUNDING_RATE(Severity.WARNING),

    /** The stop loss is further from the entry than the configured percentage (BR-29). */
    WIDE_STOP_LOSS(Severity.INFO);

    private final Severity severity;

    FuturesWarningType(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }

    /** How strongly the form shows a warning, the most severe last. */
    public enum Severity {
        INFO,
        WARNING,
        BLOCKING
    }
}
