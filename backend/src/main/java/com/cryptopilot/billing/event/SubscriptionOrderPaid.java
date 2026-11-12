package com.cryptopilot.billing.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An order was paid and the subscription it bought is active (T-069): what the receipt mail and the in-app notice
 * need, so the notification module reads nothing back from billing. Published once per order, in the transaction
 * that settles it; a repeated IPN finds the order PAID and publishes nothing.
 *
 * @param orderId the order's key, the source of the notice
 * @param userId who paid
 * @param orderCode the code shown to the Trader and sent to the gateway
 * @param packageName the name of the package bought
 * @param orderType NEW, RENEW or UPGRADE
 * @param amount what was paid, in {@code currency}
 * @param currency VND
 * @param gateway the gateway that took the payment, e.g. SEPAY
 * @param transactionNo the gateway's transaction number, or {@code null} when it sent none
 * @param paidAt when the gateway confirmed the payment
 * @param startAt when the new subscription starts
 * @param endAt when it ends, bonus days included
 * @param carriedValueVnd the unused value of the subscription it replaced; 0 for NEW
 * @param bonusDays the days the carried value bought; only an upgrade has them
 */
public record SubscriptionOrderPaid(
        UUID orderId,
        UUID userId,
        String orderCode,
        String packageName,
        String orderType,
        BigDecimal amount,
        String currency,
        String gateway,
        String transactionNo,
        Instant paidAt,
        Instant startAt,
        Instant endAt,
        BigDecimal carriedValueVnd,
        int bonusDays) {

    public SubscriptionOrderPaid {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(orderCode, "orderCode");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(paidAt, "paidAt");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        Objects.requireNonNull(carriedValueVnd, "carriedValueVnd");
    }
}
