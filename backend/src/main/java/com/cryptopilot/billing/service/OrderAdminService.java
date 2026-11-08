package com.cryptopilot.billing.service;

import com.cryptopilot.billing.dto.AdminOrderDetailResponse;
import com.cryptopilot.billing.dto.AdminOrderResponse;
import com.cryptopilot.billing.model.enums.GatewayCode;
import com.cryptopilot.billing.model.enums.OrderStatus;
import com.cryptopilot.billing.model.enums.OrderType;
import com.cryptopilot.common.web.PageResponse;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The subscription orders of every Trader, for the administrator (SCR-40, UC-48): the list, one order with what its
 * gateway answered, asking the gateway again, and recording how a flagged order was handled.
 *
 * <p>Rule: UC-48; NSF-13; BR-57; D-146.
 */
public interface OrderAdminService {

    /**
     * The orders matching every filter given, newest first.
     *
     * @param from first day of creation, in the administrator's time zone, or {@code null}
     * @param to last day of creation, included, or {@code null}
     * @param manual {@code true} for the flagged orders only, {@code false} for the others, {@code null} for all
     * @param orderCode the order code, or its beginning, or {@code null}
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG15 when the period ends before it starts,
     *     or page or pageSize is out of range
     */
    PageResponse<AdminOrderResponse> list(Criteria criteria, Integer page, Integer pageSize);

    /** The order with this code. @throws com.cryptopilot.common.exception.ResourceNotFoundException when none */
    AdminOrderDetailResponse detail(String orderCode);

    /**
     * Asks the order's gateway about it now and settles or expires it as the answer says (NSF-13); audited.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when there is no such order
     * @throws com.cryptopilot.common.exception.BusinessException PAYMENT_GATEWAY_UNAVAILABLE when the gateway
     *     cannot be asked
     */
    AdminOrderDetailResponse reconcile(UUID adminId, String orderCode);

    /**
     * Records how a flagged order was handled, e.g. refunded outside CryptoPilot; audited.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when there is no such order
     * @throws com.cryptopilot.common.exception.BusinessException ORDER_NOT_AWAITING_HANDLING when the order is not
     *     flagged or was handled already
     */
    AdminOrderDetailResponse markHandled(UUID adminId, String orderCode, String note);

    /** The filters of the list; every one may be {@code null}. */
    record Criteria(
            OrderStatus status,
            OrderType orderType,
            GatewayCode gateway,
            LocalDate from,
            LocalDate to,
            Boolean manual,
            String orderCode,
            UUID userId) {}
}
