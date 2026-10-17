package com.cryptopilot.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How the outbox processor retries and cleans the event publication registry (T-056, ADR-010).
 *
 * <p>Rule: NSF-15; ADR-010; D-08.
 *
 * @param enabled whether the processor is scheduled; off without a profile so a test context schedules nothing
 * @param interval the pause between two runs
 * @param firstRetryAfter the wait before the first retry of a failed delivery; each later retry waits twice as long
 * @param maxRetries how many times a failed delivery is retried before it is left alone
 * @param staleAfter how long a delivery may stay unfinished before it is taken for crashed and counted as failed
 * @param retention the age past which a publication is deleted, delivered or not
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.outbox")
public record OutboxProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("1m") Duration interval,
        @NotNull @DefaultValue("1m") Duration firstRetryAfter,
        @Min(0) @Max(10) @DefaultValue("3") int maxRetries,
        @NotNull @DefaultValue("5m") Duration staleAfter,
        @NotNull @DefaultValue("24h") Duration retention) {}
