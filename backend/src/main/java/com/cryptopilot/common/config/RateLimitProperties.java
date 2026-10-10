package com.cryptopilot.common.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Request rate limits (ADR-014): how many requests one subject may make in one fixed window.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.3; ADR-014.
 *
 * @param enabled off without a profile, so test contexts are not limited; on in dev and prod
 * @param window the length of one counting window
 * @param login {@code POST /api/v1/auth/login}, per client address
 * @param auth every other public {@code POST /api/v1/auth/**}, per client address
 * @param api every authenticated request under {@code /api/v1/}, per account, except placing a paper order
 * @param paperOrders {@code POST /api/v1/paper/orders}, per account, in its own window; Binance allows 50 new orders
 *     in 10 seconds, and the paper exchange follows it. Cancels and reads are not counted, as on Binance
 * @param paperOrdersWindow the length of the window of {@code paperOrders}
 */
@Validated
@ConfigurationProperties("cryptopilot.web.rate-limit")
public record RateLimitProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("1m") Duration window,
        @Min(1) @DefaultValue("10") int login,
        @Min(1) @DefaultValue("20") int auth,
        @Min(1) @DefaultValue("120") int api,
        @Min(1) @DefaultValue("50") int paperOrders,
        @NotNull @DefaultValue("10s") Duration paperOrdersWindow) {

    public RateLimitProperties {
        if (window != null && window.toSeconds() < 1) {
            throw new IllegalArgumentException("the rate-limit window is at least one second: " + window);
        }
        if (paperOrdersWindow != null && paperOrdersWindow.toSeconds() < 1) {
            throw new IllegalArgumentException(
                    "the paper order rate-limit window is at least one second: " + paperOrdersWindow);
        }
    }
}
