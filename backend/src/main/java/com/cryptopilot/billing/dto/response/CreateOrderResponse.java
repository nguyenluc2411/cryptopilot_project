package com.cryptopilot.billing.dto.response;

/**
 * A PENDING order and the way to its payment page.
 *
 * @param order the order created
 * @param checkout where the browser goes next
 */
public record CreateOrderResponse(OrderResponse order, CheckoutResponse checkout) {}
