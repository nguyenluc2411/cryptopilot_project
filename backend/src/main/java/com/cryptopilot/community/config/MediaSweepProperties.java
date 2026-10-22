package com.cryptopilot.community.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * When the sweep of files no post carries runs, and how old a file must be before it is deleted (plan 9.3: a file
 * uploaded with a signed form and never attached to a post stays in the media storage otherwise).
 *
 * @param enabled whether the sweep is scheduled; off without a profile so a test context schedules nothing
 * @param cron the Spring cron expression of the daily run
 * @param zone the zone the cron expression is read in
 * @param grace how long after its upload a file is left for its post to be published; a post published later finds
 *     its file gone and is refused with MSG32
 * @param maxPages the most listing pages read per media type and run, each one Admin API request of up to 500 files,
 *     so a run stays well inside the account's hourly Admin API allowance
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.media.sweep")
public record MediaSweepProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("0 45 0 * * *") String cron,
        @NotNull @DefaultValue("UTC") ZoneId zone,
        @NotNull @DefaultValue("24h") Duration grace,
        @Min(1) @DefaultValue("20") int maxPages) {}
