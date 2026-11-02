package com.cryptopilot.paper.model.enums;

/**
 * The kind of row that caused a ledger entry. The names are the values {@code wallet_ledger_entry.ref_type} accepts.
 * A TRANSFER is not a row: the two entries of a transfer share its id. A USER entry is the grant of the Trader's
 * opening (D-117).
 *
 * <p>Rule: TR-04.
 */
public enum LedgerRefType {
    ORDER,
    FILL,
    TRANSFER,
    USER,
    /** A Futures position: its realized profit, fees and insurance cover (D-127). */
    POSITION,
    /** A funding settlement of a Futures position; the id names the position and the settlement (D-127). */
    FUNDING
}
