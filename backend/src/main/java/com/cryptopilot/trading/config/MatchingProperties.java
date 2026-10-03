package com.cryptopilot.trading.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The partitions of the matching engine, {@code cryptopilot.trading.matching}.
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 7.7 and 10.
 *
 * @param enabled whether the engine runs at all
 * @param partitions how many single-consumer partitions share the pairs
 * @param queueCapacity commands a partition may hold; a price update beyond it waits merged with the other updates of
 *     its pair's candle, never dropped
 */
@Validated
@ConfigurationProperties("cryptopilot.trading.matching")
public record MatchingProperties(
        @DefaultValue("true") boolean enabled,
        @Min(1) @Max(64) @DefaultValue("4") int partitions,
        @Min(1) @DefaultValue("10000") int queueCapacity) {}
