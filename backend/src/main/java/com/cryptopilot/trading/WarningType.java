package com.cryptopilot.trading;

/**
 * The kinds of warning a trading plan can raise, each with the one severity its rule gives it. The names are the
 * values {@code trading_plan_warning.warning_type} accepts.
 *
 * <p>Rule: BR-25, BR-26, BR-28, BR-29.
 */
public enum WarningType {

    /** Spot notional or Futures initial margin above the capital (BR-25, BR-26). */
    INSUFFICIENT_CAPITAL(WarningSeverity.BLOCKING),

    /** Futures stop loss at or beyond the liquidation price (BR-28). */
    SL_BEYOND_LIQUIDATION(WarningSeverity.BLOCKING),

    /** Reward to risk ratio below the configured minimum (BR-29). */
    LOW_RR(WarningSeverity.WARNING),

    /** Risk % above the risk per trade of the Trader's risk profile (BR-29, BR-66). */
    OVERSIZED_POSITION(WarningSeverity.WARNING),

    /** Leverage above the maximum of the Trader's risk profile (BR-29, BR-66). */
    HIGH_LEVERAGE(WarningSeverity.WARNING),

    /** This plan's risk plus the other open risk above the risk profile's maximum (BR-29, BR-66, Q-27). */
    TOTAL_OPEN_RISK(WarningSeverity.WARNING),

    /** Stop loss further from the entry than the configured percentage (BR-29). */
    WIDE_STOP_LOSS(WarningSeverity.INFO),

    /** Funding rate at or above the configured size, on the side that pays it (BR-29). */
    HIGH_FUNDING_RATE(WarningSeverity.WARNING);

    private final WarningSeverity severity;

    WarningType(WarningSeverity severity) {
        this.severity = severity;
    }

    public WarningSeverity severity() {
        return severity;
    }
}
