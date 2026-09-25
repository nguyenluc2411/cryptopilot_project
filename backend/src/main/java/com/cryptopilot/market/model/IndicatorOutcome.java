package com.cryptopilot.market.model;

/**
 * What happened to a closed candle offered to the indicators of its series.
 *
 * <p>Rule: BR-12; NSF-05; TECHNICAL_DESIGN 7.2 and 10 (a listener of a closed candle is idempotent).
 */
public enum IndicatorOutcome {

    /** The candle follows the last one; every indicator was updated once. */
    ACCEPTED,

    /** The candle is the last one again; nothing was updated. */
    DUPLICATE,

    /** The candle opened before the last one. It was refused, and the state rebuilt from the stored candles. */
    OUT_OF_ORDER,

    /** The candle does not follow the last one: some are missing. It was refused, and the state rebuilt. */
    GAP,

    /** The series had no state in memory (first candle since start-up); it was built from the stored candles. */
    RESTORED
}
