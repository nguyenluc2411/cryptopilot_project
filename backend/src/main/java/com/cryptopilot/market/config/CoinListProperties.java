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
 *     context schedules nothing (the first request then builds the list once)
 * @param refreshInterval how often the list is rebuilt
 */
@Validated
@ConfigurationProperties("cryptopilot.market.coin-list")
public record CoinListProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("60s") Duration refreshInterval) {}
