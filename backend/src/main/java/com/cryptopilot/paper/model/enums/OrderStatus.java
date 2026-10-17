package com.cryptopilot.paper.model.enums;

import java.util.Set;

/**
 * Where a paper order stands. The names are the values {@code paper_order.order_status} accepts.
 *
 * <p>Rule: TR-02.
 */
public enum OrderStatus {

    /** Working: waits in the book. */
    NEW,

    /** The pending leg of an OTO or OTOCO, placed when its working order fills. */
    PENDING_NEW,

    /** Working, with part of its quantity executed. */
    PARTIALLY_FILLED,

    /** Executed in full. */
    FILLED,

    /** Cancelled by the Trader. */
    CANCELED,

    /** Ended by its time in force, e.g. an IOC limit that could not execute on arrival. */
    EXPIRED,

    /** Refused when placed. */
    REJECTED;

    /** The statuses of an order that still works, so it is listed as open and may be cancelled. */
    public static final Set<OrderStatus> OPEN = Set.of(NEW, PARTIALLY_FILLED);

    /** Whether the order still works. */
    public boolean isOpen() {
        return OPEN.contains(this);
    }
}
