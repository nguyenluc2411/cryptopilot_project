package com.cryptopilot.paper.model;

import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import java.time.Instant;
import java.util.UUID;

/**
 * The filters and the page of the order or trade history. Every filter is optional.
 *
 * @param pairId one pair, or {@code null} for every pair
 * @param orderId the fills of one order, or {@code null} for every order; read by the trade history only (B5)
 * @param status one status, or {@code null} for every status; ignored by the trade history
 * @param type one order type, or {@code null} for every type; ignored by the trade history (B6)
 * @param side BUY or SELL, or {@code null} for both (B6)
 * @param from the earliest instant, inclusive, or {@code null}
 * @param to the latest instant, exclusive, or {@code null}
 * @param page the page, from 1, or {@code null} for 1
 * @param pageSize 1 to 100, or {@code null} for 20
 */
public record OrderQuery(
        UUID pairId,
        UUID orderId,
        OrderStatus status,
        OrderType type,
        OrderSide side,
        Instant from,
        Instant to,
        Integer page,
        Integer pageSize) {

    /** The filters of the order history: no order id. */
    public static OrderQuery orders(
            UUID pairId,
            OrderStatus status,
            OrderType type,
            OrderSide side,
            Instant from,
            Instant to,
            Integer page,
            Integer pageSize) {
        return new OrderQuery(pairId, null, status, type, side, from, to, page, pageSize);
    }

    /** The filters of the trade history: no status, no type. */
    public static OrderQuery fills(
            UUID pairId, UUID orderId, OrderSide side, Instant from, Instant to, Integer page, Integer pageSize) {
        return new OrderQuery(pairId, orderId, null, null, side, from, to, page, pageSize);
    }
}
