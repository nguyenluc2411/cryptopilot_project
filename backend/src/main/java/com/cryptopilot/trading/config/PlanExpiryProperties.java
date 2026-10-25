package com.cryptopilot.trading.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How often ACTIVE plans whose expiry has come are expired (NSF-09: every minute).
 *
 * <p>Rule: NSF-09.
 *
 * @param enabled whether the expiry runs; off without a profile so a test context schedules nothing
 * @param interval the wait between two runs
 * @param batchSize the most plans one run expires, the oldest expiry first
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.trading.expiry")
public record PlanExpiryProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("1m") Duration interval,
        @Min(1) @DefaultValue("500") int batchSize) {}
