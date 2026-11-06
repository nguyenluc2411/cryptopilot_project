package com.cryptopilot.journal.model.enums;

/**
 * The source filter of SCR-20 and SCR-23: simulated trades, manual ones, or both. BR-41 reports them separately, so
 * SIMULATED is the default of SCR-23; the journal lists both unless told otherwise (D-140).
 *
 * <p>Rule: SRS 3.6.1, 3.6.3; UC-22, UC-25; BR-41; D-140.
 */
public enum PerformanceSource {
    SIMULATED,
    MANUAL,
    ALL
}
