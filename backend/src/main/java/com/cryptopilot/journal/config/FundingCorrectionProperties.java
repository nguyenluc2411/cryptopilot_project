package com.cryptopilot.journal.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How often the journal asks again for the funding of the closed trades whose funding was incomplete (D-97 note of
 * 2026-10-24): a settlement stored late by NSF-04 is picked up within one interval.
 *
 * <p>Rule: BR-37; NSF-04; A-05.
 *
 * @param enabled whether the correction is scheduled; off without a profile so a test context schedules nothing
 * @param interval the wait between two runs
 * @param batchSize the most trades one run recomputes, the oldest exits first
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.journal.funding-correction")
public record FundingCorrectionProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("10m") Duration interval,
        @Min(1) @DefaultValue("200") int batchSize) {}
