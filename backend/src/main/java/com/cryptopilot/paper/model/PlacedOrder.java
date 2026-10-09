package com.cryptopilot.paper.model;

import com.cryptopilot.paper.dto.response.OrderResponse;
import java.util.Objects;

/**
 * The result of a placement: the order, and whether this request placed it or found it placed already under the same
 * idempotency key.
 *
 * @param order the order
 * @param created whether this request placed it
 */
public record PlacedOrder(OrderResponse order, boolean created) {

    public PlacedOrder {
        Objects.requireNonNull(order, "order");
    }
}
