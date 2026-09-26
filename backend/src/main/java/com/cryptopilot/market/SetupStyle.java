package com.cryptopilot.market;

/**
 * The style presets a setup score is read under; each has its own timeframe and component weights.
 *
 * <p>Rule: BR-13; D-53.
 */
public enum SetupStyle {

    /** Short trades; momentum and activity weigh most. */
    SCALPING,

    /** Intraday trades; trend and momentum in balance. */
    DAY_TRADING,

    /** Multi-day trades; trend and price structure weigh most. */
    SWING
}
