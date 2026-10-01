package com.cryptopilot.trading.model.enums;

/**
 * The margin mode of a Futures plan. Isolated margin is the only one supported; a Spot plan has none. The name is the
 * value {@code trading_plan.margin_mode} accepts.
 *
 * <p>Rule: BR-26; D-10.
 */
public enum MarginMode {

    /** The position can lose at most the margin committed to it. */
    ISOLATED
}
