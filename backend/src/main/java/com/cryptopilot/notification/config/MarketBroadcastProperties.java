package com.cryptopilot.notification.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The notice every other Trader gets of a market incident (D-159), under {@code cryptopilot.notification.market-broadcast}:
 * the Traders it affects are told by their own notices and by mail; the rest get one general notice in the bell, a
 * little later, so whoever was told already is left out.
 *
 * <p>Rule: NSF-03; D-159.
 *
 * @param enabled whether the general notices are written; off without a profile so a test context schedules nothing
 * @param delay how long after the announcement the general notices are written
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.notification.market-broadcast")
public record MarketBroadcastProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("30s") Duration delay) {}
