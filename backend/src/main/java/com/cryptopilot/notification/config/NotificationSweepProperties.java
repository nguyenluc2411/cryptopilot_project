package com.cryptopilot.notification.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * When the notification retention sweep runs and what it keeps (NSF-17, daily: "Notifications older than 90 days are
 * deleted", SRS 3.4.5).
 *
 * <p>Rule: NSF-17; SRS 3.4.5.
 *
 * @param enabled whether the sweep is scheduled; off without a profile so a test context schedules nothing
 * @param cron the Spring cron expression of the daily run
 * @param zone the zone the cron expression is read in
 * @param retention how long a notification is kept
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.notification.sweep")
public record NotificationSweepProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("0 30 0 * * *") String cron,
        @NotNull @DefaultValue("UTC") ZoneId zone,
        @NotNull @DefaultValue("90d") Duration retention) {}
