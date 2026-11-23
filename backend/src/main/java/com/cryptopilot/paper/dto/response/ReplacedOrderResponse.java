package com.cryptopilot.paper.dto.response;

/**
 * What a cancel-replace of a Spot order did (B4): both halves happened, or neither did.
 *
 * @param canceled the replaced order, now CANCELED, with what it had executed before
 * @param placed the order placed in its place, as a placement answers it: waiting, or already executed in part or in
 *     whole when its new price crossed the book
 */
public record ReplacedOrderResponse(OrderResponse canceled, OrderResponse placed) {}
