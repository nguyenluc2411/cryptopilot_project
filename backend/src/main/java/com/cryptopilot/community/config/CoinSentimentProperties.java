package com.cryptopilot.community.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How the community's leaning per coin is recomputed, under {@code cryptopilot.community.coin-sentiment}.
 *
 * @param enabled whether the recompute is scheduled; off without a profile and in {@code calibration}, so a test
 *     context schedules nothing (a request then computes it when there is none or it is older than the interval)
 * @param refreshInterval how often it is recomputed, by the job or, with the job off, by a request
 */
@Validated
@ConfigurationProperties("cryptopilot.community.coin-sentiment")
public record CoinSentimentProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("60s") Duration refreshInterval) {}
