package com.cryptopilot.paper.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How the funding of paper Futures positions is settled (TR-24, plan 9.4), under {@code cryptopilot.paper.funding}.
 *
 * <p>Rule: TR-24.
 *
 * @param enabled whether the run is scheduled; off without a profile so a test context schedules nothing
 * @param interval the delay between two runs; the exchange settles every 8 hours (some pairs every 4 or 1), so a run a
 *     minute applies a settlement within a minute of its being stored
 * @param lookback how far back a run looks for settlements not applied yet, e.g. after a downtime
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.paper.funding")
public record PaperFundingProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("1m") Duration interval,
        @NotNull @DefaultValue("2d") Duration lookback) {}
