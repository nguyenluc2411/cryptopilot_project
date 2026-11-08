package com.cryptopilot.billing.dto;

import com.cryptopilot.billing.model.enums.GatewayCode;
import com.cryptopilot.billing.model.enums.OrderStatus;
import com.cryptopilot.billing.model.enums.OrderType;
import com.cryptopilot.billing.model.enums.PlanTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One subscription order in the administrator's payment list (SCR-40, UC-48).
 *
 * @param manualHandling the order was paid but changed no subscription and waits for an administrator
 * @param handledAt when an administrator recorded how it was handled, or {@code null}
 */
public record AdminOrderResponse(
        String orderCode,
        UUID userId,
        String packageCode,
        String packageName,
        PlanTier tier,
        OrderType orderType,
        BigDecimal amount,
        String currency,
        GatewayCode gateway,
        OrderStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant paidAt,
        boolean manualHandling,
        Instant handledAt) {}
