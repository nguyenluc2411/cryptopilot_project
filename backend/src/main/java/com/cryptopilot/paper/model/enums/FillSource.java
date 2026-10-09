package com.cryptopilot.paper.model.enums;

/**
 * What decided a fill. The names are the values {@code paper_fill.fill_source} accepts.
 *
 * <p>Rule: TR-02; Q-T6.
 */
public enum FillSource {

    /** The live market stream, or the placement itself. */
    LIVE,

    /** The closed 1-minute candles replayed after a restart or a lost stream (Q-T6). */
    REPLAY
}
