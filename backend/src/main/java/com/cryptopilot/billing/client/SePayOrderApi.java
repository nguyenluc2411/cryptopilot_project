package com.cryptopilot.billing.client;

import java.util.Optional;

/**
 * The SePay order query, {@code GET /v1/order/detail/{order_invoice_number}}: the raw answer, read by
 * {@link SePayGateway}. Its own type so the protocol is tested without a network.
 *
 * <p>Rule: NSF-13; D-146. Reference: SePay developer documentation, "API lấy chi tiết đơn hàng".
 */
@FunctionalInterface
public interface SePayOrderApi {

    /**
     * The JSON SePay answers for the order, or empty when it has no such order (404).
     *
     * @throws GatewayUnavailableException when SePay cannot be reached or refuses the query
     */
    Optional<String> orderDetail(String orderCode);
}
