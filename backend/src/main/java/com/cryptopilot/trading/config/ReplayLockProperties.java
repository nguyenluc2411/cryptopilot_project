package com.cryptopilot.trading.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The Redis lease that marks a pair's restart replay as running (D-85): how long it lives without a heartbeat, and how
 * often the holder renews it. The TTL must exceed two heartbeats, so one late or lost renewal does not free a lease
 * whose replay is still running.
 *
 * <p>Rule: NSF-07; D-79, D-85.
 *
 * @param ttl how long the key lives after the last renewal; what a crashed holder leaves behind
 * @param heartbeat how often a holder renews its keys
 */
@Validated
@ConfigurationProperties("cryptopilot.trading.matching.replay.lock")
public record ReplayLockProperties(
        @NotNull @DefaultValue("90s") Duration ttl,
        @NotNull @DefaultValue("30s") Duration heartbeat) {

    public ReplayLockProperties {
        if (ttl != null && heartbeat != null) {
            if (!heartbeat.isPositive()) {
                throw new IllegalArgumentException("the replay lock heartbeat must be positive: " + heartbeat);
            }
            if (ttl.compareTo(heartbeat.multipliedBy(2)) <= 0) {
                throw new IllegalArgumentException(
                        "the replay lock TTL (" + ttl + ") must be more than twice the heartbeat (" + heartbeat + ")");
            }
        }
    }
}
