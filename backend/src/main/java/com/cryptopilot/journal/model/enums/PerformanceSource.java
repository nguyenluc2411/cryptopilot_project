package com.cryptopilot.journal.model.enums;

/**
 * The source filter of SCR-23: simulated trades, manual ones, or both. BR-41 reports them separately, so SIMULATED is
 * the default.
 *
 * <p>Rule: SRS 3.6.3; UC-25; BR-41.
 */
public enum PerformanceSource {
    SIMULATED,
    MANUAL,
    ALL
}
