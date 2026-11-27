package com.cryptopilot.journal.model.enums;

/**
 * Where a journal record comes from. Since V77 a SIMULATED record may also be opened by the paper order a plan was
 * applied with, and a DEMO_ORDER record by any other paper order. The names are the values {@code trading_journal.source} accepts.
 *
 * <p>Rule: BR-34, BR-41.
 */
public enum TradeSource {

    /** Opened by the fill of a trading plan; its execution facts are the system's. */
    SIMULATED,

    /** Entered by the Trader (UC-24); it has no plan. */
    MANUAL,

    /** Opened by a paper order the Trader placed directly, not by applying a plan (BM04 F-06; DEF-03). */
    DEMO_ORDER
}
