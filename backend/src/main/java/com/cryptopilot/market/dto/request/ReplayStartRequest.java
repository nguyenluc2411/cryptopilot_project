package com.cryptopilot.market.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An administrator starts a price replay on a test environment (RF-07).
 *
 * @param scenario the name of a scenario in {@code market/replay}, e.g. {@code btc_liq}
 */
public record ReplayStartRequest(
        @NotBlank(message = "MSG01") @Size(max = 64, message = "MSG01")
        String scenario) {}
