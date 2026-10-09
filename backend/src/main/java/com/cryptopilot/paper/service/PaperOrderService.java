package com.cryptopilot.paper.service;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.paper.dto.request.PlaceOrderRequest;
import com.cryptopilot.paper.dto.response.FillResponse;
import com.cryptopilot.paper.dto.response.OrderResponse;
import com.cryptopilot.paper.model.OrderQuery;
import com.cryptopilot.paper.model.PlacedOrder;
import java.util.List;
import java.util.UUID;

/**
 * The orders of a Trader's paper account (TR-02): place, cancel, and read the open orders, the order history and the
 * trade history. This stage places Spot MARKET and LIMIT orders.
 *
 * <p>Every call needs the account opened (MSG41 otherwise), and only ever reads or changes the caller's own orders.
 *
 * <p>Rule: TR-02.
 */
public interface PaperOrderService {

    /**
     * Places an order, once per idempotency key.
     *
     * <ul>
     *   <li>MARKET executes at once at the current last price, as a taker.
     *   <li>LIMIT executes at once, as a taker at the current last price, when that price is already at or better than
     *       its own; otherwise a GTC order locks what it needs and waits, and an IOC or FOK order expires.
     * </ul>
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when the account is not opened, or the pair
     *     is not enabled on the market
     * @throws com.cryptopilot.common.exception.FieldValidationException when a field does not fit the order type or
     *     the pair's filters (MSG01, MSG15)
     * @throws com.cryptopilot.common.exception.BusinessException {@code PAPER_INSUFFICIENT_BALANCE} when the Spot
     *     wallet holds less free than the order needs; {@code MARKET_PRICE_UNAVAILABLE} when an order that must
     *     execute now finds no current price; {@code DATA_CONFLICT} when the idempotency key was used for an order of
     *     other values
     */
    PlacedOrder place(UUID userId, PlaceOrderRequest request);

    /**
     * Cancels a working order and gives back what it locked.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when the caller has no such order
     * @throws com.cryptopilot.common.exception.BusinessException {@code PAPER_ORDER_NOT_OPEN} when it has finished
     */
    OrderResponse cancel(UUID userId, UUID orderId);

    /** One order of the caller; MSG41 when there is none. */
    OrderResponse order(UUID userId, UUID orderId);

    /** The caller's working orders, newest first, of one pair or of all when {@code pairId} is {@code null}. */
    List<OrderResponse> openOrders(UUID userId, UUID pairId);

    /** The caller's orders, newest first, filtered and paged. */
    PageResponse<OrderResponse> orders(UUID userId, OrderQuery query);

    /** The caller's fills, newest first, filtered and paged; the status filter does not apply. */
    PageResponse<FillResponse> fills(UUID userId, OrderQuery query);
}
