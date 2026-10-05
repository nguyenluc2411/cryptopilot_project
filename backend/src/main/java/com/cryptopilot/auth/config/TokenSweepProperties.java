package com.cryptopilot.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * When the refresh-token retention sweep runs (NSF-17, daily).
 *
 * <p>Rule: NSF-17.
 *
 * @param enabled whether the sweep is scheduled; off without a profile so a test context schedules nothing
 * @param cron the Spring cron expression of the daily run
 * @param zone the zone the cron expression is read in
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.auth.token-sweep")
public record TokenSweepProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("0 15 0 * * *") String cron,
        @NotNull @DefaultValue("UTC") ZoneId zone) {}
