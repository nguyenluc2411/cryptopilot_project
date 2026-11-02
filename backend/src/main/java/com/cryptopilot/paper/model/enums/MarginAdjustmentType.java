package com.cryptopilot.paper.model.enums;

/**
 * What changed an isolated wallet. The names are the values {@code futures_margin_adjustment.adjustment_type}
 * accepts.
 *
 * <p>Rule: TR-16, TR-17, TR-22, TR-24; D-127.
 */
public enum MarginAdjustmentType {

    /** The fill that opened the position moved margin in from the cross wallet. */
    OPEN,

    /** A fill that increased the position moved margin in. */
    INCREASE,

    /** The Trader added margin. */
    MANUAL_ADD,

    /** The Trader removed margin. */
    MANUAL_REDUCE,

    /** A reduction released its share of the wallet to the cross wallet. */
    RELEASE,

    /** A reduction's loss beyond the released share was taken from the wallet. */
    LOSS_ABSORB,

    /** The position closed and the wallet went back to the cross wallet. */
    CLOSE,

    /** The position was liquidated. */
    LIQUIDATION,

    /** A funding fee was received into or paid from the wallet. */
    FUNDING
}
