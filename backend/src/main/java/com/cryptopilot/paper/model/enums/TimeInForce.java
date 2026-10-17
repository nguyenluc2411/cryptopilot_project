package com.cryptopilot.paper.model.enums;

/**
 * How long a limit order works. The names are the values {@code paper_order.time_in_force} accepts.
 *
 * <p>Rule: TR-02.
 */
public enum TimeInForce {

    /** Good till cancelled: waits at its price until it fills or the Trader cancels it. */
    GTC,

    /** Immediate or cancel: what executes on arrival executes, the rest expires. */
    IOC,

    /** Fill or kill: executes in full on arrival or expires whole. */
    FOK,

    /** Post only (Futures): refused when it would execute on arrival. */
    GTX
}
