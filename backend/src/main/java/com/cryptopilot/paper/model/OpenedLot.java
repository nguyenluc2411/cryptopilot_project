package com.cryptopilot.paper.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One part of a position as it was opened: a quantity at a price, which the open price calculator averages (TR-25).
 *
 * @param price the price it opened at
 * @param quantity the quantity opened
 */
public record OpenedLot(BigDecimal price, BigDecimal quantity) {

    public OpenedLot {
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(quantity, "quantity");
    }
}
