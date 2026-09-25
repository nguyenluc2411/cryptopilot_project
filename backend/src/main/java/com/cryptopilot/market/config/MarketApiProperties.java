package com.cryptopilot.market.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The limits of the public market data API (UC-09), under {@code cryptopilot.market.api}.
 *
 * <p>Every request is bounded, in points and in time, so that one call can never read a whole hypertable. The
 * defaults serve the screens: SCR-10 draws the latest 500 closed candles and SCR-01 the latest 90 daily ones; the
 * futures metrics of SCR-11 are 5-minute series (NSF-04), so seven days is 2,016 points per series.
 *
 * <p>Rule: UC-09; SRS 3.3.1, 3.3.2, 3.3.3; BR-08, BR-10.
 *
 * @param defaultCandles candles returned when the request names no limit
 * @param maxCandles the most candles one request may return, and the longest range it may ask for, in candles
 * @param defaultMetricsRange the metrics range when the request names no start
 * @param maxMetricsRange the longest metrics range one request may ask for
 */
@Validated
@ConfigurationProperties("cryptopilot.market.api")
public record MarketApiProperties(
        @Min(1) @Max(1000) @DefaultValue("500") int defaultCandles,
        @Min(1) @Max(1000) @DefaultValue("1000") int maxCandles,
        @NotNull @DefaultValue("1d") Duration defaultMetricsRange,
        @NotNull @DefaultValue("7d") Duration maxMetricsRange) {}
