package com.cryptopilot.paper.model;

import com.cryptopilot.paper.dto.response.OrderListResponse;
import java.util.Objects;

/**
 * The result of a group placement (TR-10): the group, and whether this request placed it or found it placed already
 * under the same {@code listClientOrderId}.
 *
 * @param list the group with its orders
 * @param created whether this request placed it
 */
public record PlacedOrderList(OrderListResponse list, boolean created) {

    public PlacedOrderList {
        Objects.requireNonNull(list, "list");
    }
}
