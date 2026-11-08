package com.cryptopilot.billing.dto;

import com.cryptopilot.billing.model.enums.GatewayCode;
import com.cryptopilot.billing.model.enums.OrderStatus;
import com.cryptopilot.billing.model.enums.OrderType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * An order of the caller (SCR-31, SCR-32). SCR-31 reads the result from {@code status}, never from the return URL.
 *
 * @param packageCode the package bought
 * @param paidAt {@code null} until PAID
 */
public record OrderResponse(
        String orderCode,
        String packageCode,
        String packageName,
        OrderType orderType,
        BigDecimal amount,
        String currency,
        GatewayCode gateway,
        OrderStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant paidAt) {}
