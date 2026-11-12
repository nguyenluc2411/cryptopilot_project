package com.cryptopilot.billing.dto.response;

import com.cryptopilot.billing.model.enums.SubscriptionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One subscription order with what its gateway answered and what it activated (SCR-40, UC-48).
 *
 * @param order the order as the list shows it
 * @param gatewayTransactionNo the gateway's transaction reference, or {@code null}
 * @param gatewayResponseCode the gateway's last status or result code, or {@code null}
 * @param gatewayPayload the gateway's last message (IPN or query answer) as JSON text, or {@code null}
 * @param ipnReceivedAt when the gateway's last answer was recorded, or {@code null}
 * @param handledBy the administrator who handled a flagged order, or {@code null}
 * @param handlingNote how it was handled, or {@code null}
 * @param subscription the subscription the order opened, or {@code null} when it opened none
 */
public record AdminOrderDetailResponse(
        AdminOrderResponse order,
        String gatewayTransactionNo,
        String gatewayResponseCode,
        String gatewayPayload,
        Instant ipnReceivedAt,
        UUID handledBy,
        String handlingNote,
        Subscription subscription) {

    /** The subscription an order opened, as it stands now. */
    public record Subscription(
            SubscriptionStatus status, Instant startAt, Instant endAt, BigDecimal carriedValueVnd, int bonusDays) {}
}
