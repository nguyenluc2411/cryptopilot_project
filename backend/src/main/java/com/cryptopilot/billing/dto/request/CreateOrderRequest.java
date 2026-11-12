package com.cryptopilot.billing.dto.request;

import com.cryptopilot.billing.model.enums.GatewayCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The package a Trader buys and the gateway they pay through (SCR-30). */
public record CreateOrderRequest(
        @NotBlank @Size(max = 32) String packageCode,
        @NotNull GatewayCode gateway) {}
