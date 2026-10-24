package com.cryptopilot.paper.model.enums;

/**
 * The kind of a group of paper orders placed together (TR-10). The names are the values
 * {@code order_list.list_type} accepts.
 *
 * <p>Rule: TR-10.
 */
public enum OrderListType {

    /** One cancels the other: a take profit and a stop limit on the same quantity; one executing cancels the other. */
    OCO,

    /** One triggers the other: a working limit order, and a pending order placed when it fills. */
    OTO,

    /** One triggers an OCO: a working limit order, and a pending OCO placed when it fills (a limit with TP/SL). */
    OTOCO
}
