package com.cryptopilot.market.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A symbol to add to the system from the exchange's list (SCR-37, UC-45). Case is ignored.
 *
 * <p>Rule: UC-45; NSF-01.
 *
 * @param symbol the exchange symbol, e.g. {@code SOLUSDT}
 */
public record RegisterPairRequest(
        @NotBlank(message = "MSG01") @Size(max = 32, message = "MSG01")
        String symbol) {}
