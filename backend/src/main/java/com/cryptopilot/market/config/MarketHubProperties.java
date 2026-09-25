package com.cryptopilot.market.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The market topics of T-022, under {@code cryptopilot.market.hub}.
 *
 * <p>Rule: NSF-03; SRS 4.2.3 (realtime price delivery within 2 s); TECHNICAL_DESIGN 9.
 *
 * @param enabled whether the pushes are scheduled; off by default and in tests, on in dev and prod
 * @param updateInterval how often the ticker and kline topics are pushed; each destination at most once per interval
 *     (TECHNICAL_DESIGN 9: at most one ticker message per second per destination)
 * @param overviewInterval how often each market's overview is pushed (TECHNICAL_DESIGN 9: every 2 s)
 */
@Validated
@ConfigurationProperties("cryptopilot.market.hub")
public record MarketHubProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("1s") Duration updateInterval,
        @NotNull @DefaultValue("2s") Duration overviewInterval) {}
