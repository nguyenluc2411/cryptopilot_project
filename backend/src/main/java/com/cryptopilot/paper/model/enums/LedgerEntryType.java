package com.cryptopilot.paper.model.enums;

/**
 * What changed a paper balance. The names are the values {@code paper_ledger_entry.entry_type} accepts.
 *
 * <p>Rule: TR-04.
 */
public enum LedgerEntryType {

    /** The virtual funds a new account is opened with, granted once (Q-T5). */
    INITIAL_GRANT,

    /** A coin bought or sold by a Spot fill. */
    TRADE,

    /** The commission of a fill. */
    FEE,

    /** The profit or loss a Futures fill realised. */
    REALIZED_PNL,

    /** The funding fee a position paid or received at a settlement. */
    FUNDING_FEE,

    /** A move between the Spot and Futures wallets. */
    TRANSFER,

    /** The clearance fee of a liquidation. */
    LIQUIDATION_FEE
}
