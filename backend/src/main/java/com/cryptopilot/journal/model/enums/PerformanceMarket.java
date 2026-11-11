package com.cryptopilot.journal.model.enums;

/**
 * The market filter of SCR-20 and SCR-23: one market, or both.
 *
 * <p>Rule: SRS 3.6.1, 3.6.3; UC-22, UC-25; D-140.
 */
public enum PerformanceMarket {
    SPOT,
    FUTURES,
    ALL
}
