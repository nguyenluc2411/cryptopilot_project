package com.cryptopilot.journal.model.enums;

/**
 * The market filter of SCR-23: one market, or both.
 *
 * <p>Rule: SRS 3.6.3; UC-25.
 */
public enum PerformanceMarket {
    SPOT,
    FUTURES,
    ALL
}
