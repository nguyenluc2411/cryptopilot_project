package com.cryptopilot.paper.model.enums;

import java.math.BigDecimal;

/**
 * Which way a paper Futures position faces. The names are the values {@code futures_position.direction} accepts.
 *
 * <p>Rule: TR-14; D-127.
 */
public enum PositionDirection {

    /** Gains when the price rises. */
    LONG(BigDecimal.ONE),

    /** Gains when the price falls. */
    SHORT(BigDecimal.ONE.negate());

    private final BigDecimal sign;

    PositionDirection(BigDecimal sign) {
        this.sign = sign;
    }

    /** {@code +1} for LONG, {@code −1} for SHORT: the {@code side} of the Futures formulas. */
    public BigDecimal sign() {
        return sign;
    }

    /** The side of the order that closes a position of this direction. */
    public OrderSide closingSide() {
        return this == LONG ? OrderSide.SELL : OrderSide.BUY;
    }

    /** The direction a fill of this side opens or increases. */
    public static PositionDirection openedBy(OrderSide side) {
        return side == OrderSide.BUY ? LONG : SHORT;
    }
}
