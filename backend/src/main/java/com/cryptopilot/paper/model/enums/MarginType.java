package com.cryptopilot.paper.model.enums;

/**
 * How a paper Futures position is margined, per pair as on Binance. The names are the values
 * {@code futures_symbol_config.margin_type} and {@code futures_position.margin_type} accept.
 *
 * <p>Rule: TR-14; D-127.
 */
public enum MarginType {

    /** The position shares the cross wallet with every other cross position. */
    CROSSED,

    /** The position has its own isolated wallet; its loss never reaches the cross wallet. */
    ISOLATED
}
