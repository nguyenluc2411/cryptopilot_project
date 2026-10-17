package com.cryptopilot.paper.model.enums;

/**
 * How a paper account holds Futures positions (Q-T4). The names are the values {@code position_mode} accepts.
 *
 * <p>Rule: TR-04; Q-T4.
 */
public enum PositionMode {

    /** One position per pair, long or short; an opposite order reduces it. The default, as on Binance. */
    ONE_WAY,

    /** A long and a short position per pair, held at the same time. */
    HEDGE
}
