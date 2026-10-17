package com.cryptopilot.paper.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * When the daily equity snapshot of every paper account is taken (TR-13): at 00:00 UTC, as Binance closes its day.
 *
 * <p>Rule: TR-13.
 *
 * @param enabled whether the snapshot is scheduled; off without a profile so a test context schedules nothing
 * @param cron the Spring cron expression of the daily run
 * @param zone the zone the cron expression is read in, and the day a snapshot belongs to
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.paper.equity-snapshot")
public record PaperEquityProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("0 0 0 * * *") String cron,
        @NotNull @DefaultValue("UTC") ZoneId zone) {}
