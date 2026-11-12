package com.cryptopilot.billing.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * How an administrator handled a flagged order, e.g. "refunded 99,000 VND by bank transfer on 2026-11-09" (UC-48).
 *
 * @param note 1 to 1,000 characters
 */
public record HandleOrderRequest(@NotBlank @Size(max = 1000) String note) {}
