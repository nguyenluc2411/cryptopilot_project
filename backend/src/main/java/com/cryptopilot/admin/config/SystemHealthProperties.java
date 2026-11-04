package com.cryptopilot.admin.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The health panel of SCR-44, under {@code cryptopilot.admin.system-health}.
 *
 * <p>Rule: UC-52; BR-08 (the stored timeframes).
 *
 * @param feedGrace how long after its shortest stored timeframe has passed a market's latest candle may still be
 *     missing before the feed reads stale: the stream writes a closed candle a few seconds after it closes
 */
@Validated
@ConfigurationProperties("cryptopilot.admin.system-health")
public record SystemHealthProperties(
        @NotNull @DefaultValue("2m") Duration feedGrace) {}
