package com.cryptopilot.paper.model.enums;

/**
 * How long a limit order works. The names are the values {@code trade_order.time_in_force} accepts.
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
    GTX,

    /** Good till date (Futures): waits as GTC until its {@code goodTillDate}, then expires (TR-44). */
    GTD;

    /** Whether an order of it is decided on arrival, filled or expired then: IOC and FOK. */
    public boolean immediate() {
        return this == IOC || this == FOK;
    }

    /** Whether only a Futures order takes it: Spot has neither post only as a time in force nor GTD. */
    public boolean futuresOnly() {
        return this == GTX || this == GTD;
    }
}
