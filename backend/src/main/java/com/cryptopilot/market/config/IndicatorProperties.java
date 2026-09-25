package com.cryptopilot.market.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The incremental indicators of T-025, under {@code cryptopilot.market.indicators}.
 *
 * <p>Rule: BR-12; TECHNICAL_DESIGN 7.2.
 *
 * @param historyCandles how many of the latest stored closed candles a series is rebuilt from (TECHNICAL_DESIGN 7.2:
 *     1000, which leaves EMA200 within 0.05% of its value over the whole history); at least the 200 EMA200 needs
 */
@Validated
@ConfigurationProperties("cryptopilot.market.indicators")
public record IndicatorProperties(
        @Min(200) @DefaultValue("1000") int historyCandles) {}
