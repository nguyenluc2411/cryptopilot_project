package com.cryptopilot.billing.model;

import com.cryptopilot.billing.model.enums.GatewayCode;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * What a gateway reported about an order, in the billing module's terms, whichever gateway sent it (IPN or a query).
 *
 * @param gateway the gateway that reported it
 * @param orderCode the order it is about
 * @param paid true for a confirmed payment, false for a failed or cancelled one
 * @param amount the amount the gateway says it collected, or {@code null} when it collected nothing
 * @param transactionNo the gateway's transaction reference, or {@code null}
 * @param responseCode the gateway's status or result code, kept on the order
 * @param payload the gateway's message as JSON, kept for the administrator (SCR-40)
 */
public record PaymentResult(
        GatewayCode gateway,
        String orderCode,
        boolean paid,
        BigDecimal amount,
        String transactionNo,
        String responseCode,
        String payload) {

    public PaymentResult {
        Objects.requireNonNull(gateway, "gateway must not be null");
        Objects.requireNonNull(orderCode, "orderCode must not be null");
        if (paid && amount == null) {
            throw new IllegalArgumentException("a confirmed payment names the amount collected");
        }
    }
}
