package com.cryptopilot.paper.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How often the granted coins that had no price when their account opened are valued again (PT-R13).
 *
 * <p>Rule: TR-13; PT-R13.
 *
 * @param enabled whether the run is scheduled; off without a profile so a test context schedules nothing
 * @param interval the delay between two runs
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.paper.grant-cost")
public record PaperGrantCostProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("5m") Duration interval) {}
