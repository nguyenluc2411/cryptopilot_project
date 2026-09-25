package com.cryptopilot.market.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The latest-price cache of T-022, under {@code cryptopilot.market.cache}.
 *
 * <p>Rule: NSF-03; TECHNICAL_DESIGN 5.6; ADR-005.
 *
 * @param enabled whether the flush to Redis is scheduled; off by default and in tests, on in dev and prod
 * @param ttl how long a price lives in Redis after its last write, and how old a price may be before a read reports
 *     it expired (TECHNICAL_DESIGN 5.6: 5 minutes)
 * @param flushInterval how often the prices gathered from the streams are written to Redis
 */
@Validated
@ConfigurationProperties("cryptopilot.market.cache")
public record PriceCacheProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("5m") Duration ttl,
        @NotNull @DefaultValue("250ms") Duration flushInterval) {}
