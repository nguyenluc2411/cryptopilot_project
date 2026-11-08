package com.cryptopilot.billing.model;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * What a gateway needs to open its payment page for an order.
 *
 * @param orderCode the order's code, which the gateway sends back in its IPN
 * @param amount the amount in VND
 * @param description the line the Trader sees on the gateway's page
 * @param returnUrl where the gateway sends the browser afterwards; it carries the order code (D-146)
 * @param expiresAt when the order stops being payable (BR-54)
 */
public record CheckoutRequest(
        String orderCode, BigDecimal amount, String description, URI returnUrl, Instant expiresAt) {

    public CheckoutRequest {
        Objects.requireNonNull(orderCode, "orderCode must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(returnUrl, "returnUrl must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
