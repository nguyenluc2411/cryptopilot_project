package com.cryptopilot.market.dto.request;

import com.cryptopilot.market.model.enums.MarketType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An administrator starts or ends maintenance of a market (D-158).
 *
 * @param market the market
 * @param on {@code true} to close trading on it, {@code false} to end the maintenance
 * @param message what the Traders' banner says, at most 500 characters; ignored when ending
 */
public record MaintenanceRequest(
        @NotNull(message = "MSG01") MarketType market,
        @NotNull(message = "MSG01") Boolean on,
        @Size(max = 500, message = "MSG01") String message) {}
