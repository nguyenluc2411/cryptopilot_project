package com.cryptopilot.paper.model.enums;

/**
 * Where a group of paper orders stands. The names are the values {@code paper_order_list.list_status} accepts.
 *
 * <p>Rule: TR-10.
 */
public enum OrderListStatus {

    /** At least one of its orders works or waits to be placed. */
    EXECUTING,

    /** Every one of its orders has finished. */
    ALL_DONE,

    /** Refused when placed; never stored, as a refusal stores nothing. */
    REJECTED
}
