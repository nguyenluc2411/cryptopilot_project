package com.cryptopilot.paper.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How often the Spot paper orders left open on a pair the exchange stopped trading are looked for and cancelled
 * (NSF-01), {@code cryptopilot.paper.pair-stop-sweep}.
 *
 * <p>Rule: TR-02; NSF-01.
 *
 * @param enabled whether the run is scheduled; off without a profile so a test context schedules nothing
 * @param interval the delay between two runs
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.paper.pair-stop-sweep")
public record PaperPairStopSweepProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("5m") Duration interval) {}
