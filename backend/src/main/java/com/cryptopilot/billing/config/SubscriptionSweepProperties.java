package com.cryptopilot.billing.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The hourly upkeep of subscriptions (NSF-14): expiry and the reminder before the end.
 *
 * @param enabled whether the run is scheduled; off without a profile so a test context schedules nothing
 * @param cron when it runs, in UTC; hourly by default
 * @param remindBefore how long before its end a subscription is reminded (3 days)
 * @param batchSize the most subscriptions one run expires, and the most it reminds; the rest wait an hour
 */
@Validated
@ConfigurationProperties("cryptopilot.billing.subscription-sweep")
public record SubscriptionSweepProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("0 0 * * * *") String cron,
        @NotNull @DefaultValue("P3D") Duration remindBefore,
        @Min(1) @Max(5000) @DefaultValue("500") int batchSize) {}
