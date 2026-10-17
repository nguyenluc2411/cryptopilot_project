package com.cryptopilot.paper.model.enums;

/**
 * The kind of row that caused a ledger entry. The names are the values {@code paper_ledger_entry.ref_type} accepts.
 *
 * <p>Rule: TR-04.
 */
public enum LedgerRefType {
    ORDER,
    FILL,
    POSITION,
    TRANSFER,
    FUNDING,
    ACCOUNT
}
