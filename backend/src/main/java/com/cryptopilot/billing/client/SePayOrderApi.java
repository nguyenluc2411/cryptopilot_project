package com.cryptopilot.billing.client;

import java.util.Optional;

/**
 * The SePay order API: the query {@code GET /v1/order/detail/{order_invoice_number}}, its raw answer read by
 * {@link SePayGateway}, and the cancellation {@code POST /v1/order/cancel}. Its own type so the protocol is tested
 * without a network.
 *
 * <p>Rule: NSF-13; D-147. Reference: SePay developer documentation, "API lấy chi tiết đơn hàng", "API hủy đơn hàng".
 */
public interface SePayOrderApi {

    /**
     * The JSON SePay answers for the order, or empty when it has no such order (404).
     *
     * @throws GatewayUnavailableException when SePay cannot be reached or refuses the query
     */
    Optional<String> orderDetail(String orderCode);

    /**
     * Cancels the unpaid order: allowed for bank-transfer orders that are neither CAPTURED nor CANCELLED.
     *
     * @return what SePay answered
     * @throws GatewayUnavailableException when SePay cannot be reached, or answers with a server error
     */
    CancelAnswer cancel(String orderCode);

    /** What SePay answered to a cancellation. */
    enum CancelAnswer {
        /** 2xx: cancelled. */
        CANCELLED,
        /** 404: SePay has no such order; the Trader never reached its page. */
        UNKNOWN,
        /** Another 4xx: already paid or cancelled, or a payment method that cannot be cancelled. */
        REFUSED
    }
}
