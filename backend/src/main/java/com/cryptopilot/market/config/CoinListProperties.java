package com.cryptopilot.market.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How the market list of coins is rebuilt, under {@code cryptopilot.market.coin-list}.
 *
 * @param enabled whether the rebuild is scheduled; off without a profile and in {@code calibration}, so a test
 *     context schedules nothing (a request then builds the list when it has none or it is older than the interval)
 * @param refreshInterval how often the list is rebuilt, by the job or, with the job off, by a request
 */
@Validated
@ConfigurationProperties("cryptopilot.market.coin-list")
public record CoinListProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("60s") Duration refreshInterval) {}
