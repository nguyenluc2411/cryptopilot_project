package com.cryptopilot.market.model.enums;

/**
 * Why a setup score was capped below its weighted sum.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4.
 */
public enum CapReason {
    /** Too little room to the next level on the scored side. */
    LOW_ROOM
}
