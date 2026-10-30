package com.cryptopilot.paper.model.enums;

/**
 * The row that caused a change of an isolated wallet. The names are the values
 * {@code futures_margin_adjustment.ref_type} accepts.
 *
 * <p>Rule: TR-16; D-127.
 */
public enum MarginAdjustmentRefType {

    /** An order fill. */
    FILL,

    /** A ledger entry, e.g. the insurance cover of a loss beyond the wallet. */
    LEDGER,

    /** A funding settlement. */
    FUNDING
}
