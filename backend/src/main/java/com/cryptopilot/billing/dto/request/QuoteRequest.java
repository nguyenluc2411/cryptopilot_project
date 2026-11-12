package com.cryptopilot.billing.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The package a Trader considers buying (SCR-30). */
public record QuoteRequest(@NotBlank @Size(max = 32) String packageCode) {}
