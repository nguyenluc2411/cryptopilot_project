package com.cryptopilot.trading.model.enums;

/**
 * Why a waiting plan became INVALIDATED before its entry filled. The names are the values
 * {@code trading_plan.invalidation_reason} accepts.
 *
 * <p>Rule: BR-68; D-111.
 */
public enum InvalidationReason {

    /** A LIMIT plan's take profit was reached first: the move it waited for happened without it. */
    TAKE_PROFIT_REACHED,

    /** A STOP plan's stop loss was reached first: the breakout failed before it triggered. */
    STOP_LOSS_REACHED,

    /** The plan's own invalidation price was reached. */
    INVALIDATION_PRICE
}
