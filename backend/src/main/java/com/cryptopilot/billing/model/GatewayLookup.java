package com.cryptopilot.billing.model;

import java.util.Objects;

/**
 * What a gateway answers when asked about one order (NSF-13), in the billing module's terms.
 *
 * <p>Only {@link State#PAID} settles anything. A cancelled or unknown order is never failed from a lookup: the order
 * just expires when it is due, and stays payable (A-03), so a payment that still arrives later is honoured.
 *
 * @param state what the gateway knows of the order
 * @param payment the confirmed payment, present exactly when {@code state} is {@link State#PAID}
 */
public record GatewayLookup(State state, PaymentResult payment) {

    /** What the gateway knows of an order. */
    public enum State {
        /** Paid in full or in part; {@code payment} says how much. */
        PAID,
        /** Opened and not paid yet. */
        AWAITING,
        /** Cancelled on the gateway's page. */
        CANCELLED,
        /** The gateway has no such order: the Trader never reached its page. */
        UNKNOWN
    }

    public GatewayLookup {
        Objects.requireNonNull(state, "state must not be null");
        if ((state == State.PAID) != (payment != null)) {
            throw new IllegalArgumentException("a payment comes with PAID and only with PAID, was " + state);
        }
        if (payment != null && !payment.paid()) {
            throw new IllegalArgumentException("a PAID lookup carries a confirmed payment");
        }
    }

    public static GatewayLookup paid(PaymentResult payment) {
        return new GatewayLookup(State.PAID, Objects.requireNonNull(payment, "payment must not be null"));
    }

    public static GatewayLookup of(State state) {
        return new GatewayLookup(state, null);
    }
}
