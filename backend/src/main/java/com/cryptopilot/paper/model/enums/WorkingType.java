package com.cryptopilot.paper.model.enums;

/**
 * The price a Futures conditional order watches for its trigger. The exchange names the last price
 * {@code CONTRACT_PRICE}; the column keeps the names of V21.
 *
 * <p>Rule: TR-20.
 */
public enum WorkingType {

    /** The last traded price, the exchange's {@code CONTRACT_PRICE}. */
    LAST_PRICE,

    /** The mark price. */
    MARK_PRICE;

    /** The exchange's name of the price. */
    public String binanceName() {
        return this == LAST_PRICE ? "CONTRACT_PRICE" : "MARK_PRICE";
    }
}
