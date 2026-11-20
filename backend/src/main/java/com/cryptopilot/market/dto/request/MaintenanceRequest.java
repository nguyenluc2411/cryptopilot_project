package com.cryptopilot.market.dto.request;

import com.cryptopilot.market.model.enums.MarketType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * An administrator starts or ends maintenance of a market (D-159).
 *
 * @param market the market
 * @param on {@code true} to close trading on it, {@code false} to end the maintenance
 * @param message what the Traders' banner says, at most 500 characters, shown by the clients as plain text, never
 *     as markup; ignored when ending
 * @param expectedEndAt when the maintenance is expected to end, in the future, shown on the banner; optional, ignored
 *     when ending
 */
public record MaintenanceRequest(
        @NotNull(message = "MSG01") MarketType market,
        @NotNull(message = "MSG01") Boolean on,
        @Size(max = 500, message = "MSG01") String message,
        Instant expectedEndAt) {

    /** A request with no expected end. */
    public MaintenanceRequest(MarketType market, Boolean on, String message) {
        this(market, on, message, null);
    }
}
