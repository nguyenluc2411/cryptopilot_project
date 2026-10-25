package com.cryptopilot.journal.model.enums;

/**
 * Where a journal record comes from. The names are the values {@code trading_journal.source} accepts.
 *
 * <p>Rule: BR-34, BR-41.
 */
public enum TradeSource {

    /** Opened by the fill of a trading plan; its execution facts are the system's. */
    SIMULATED,

    /** Entered by the Trader (UC-24); it has no plan. */
    MANUAL
}
