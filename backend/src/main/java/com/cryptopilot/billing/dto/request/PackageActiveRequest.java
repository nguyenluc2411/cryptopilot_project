package com.cryptopilot.billing.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * Puts a package on offer or takes it off (SCR-39, UC-42). The FREE package stays active.
 *
 * @param active {@code false} to deactivate
 */
public record PackageActiveRequest(
        @NotNull(message = "MSG01") Boolean active) {}
