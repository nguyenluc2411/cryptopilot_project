package com.cryptopilot.market.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * When a market's exchange data counts as lost and when it counts as back (D-158), under
 * {@code cryptopilot.market.health}.
 *
 * <p>Rule: NSF-03; D-158.
 *
 * @param enabled whether the monitor watches the streams; off, a market is only closed by maintenance
 * @param reconnectingAfter how long without data before a market is reconnecting (no notice, stale rules apply)
 * @param pauseAfter how long without data before trading on the market is paused
 * @param stableBeforeRecover how long the data must flow without a break before a paused market starts recovering
 * @param notifyAfter how long an outage must last before the Traders are told of it; maintenance is told at once
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.market.health")
public record MarketHealthProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("10s") Duration reconnectingAfter,
        @NotNull @DefaultValue("30s") Duration pauseAfter,
        @NotNull @DefaultValue("15s") Duration stableBeforeRecover,
        @NotNull @DefaultValue("2m") Duration notifyAfter) {

    @ConstructorBinding
    public MarketHealthProperties {
        if (reconnectingAfter.compareTo(pauseAfter) >= 0) {
            throw new IllegalArgumentException("reconnecting-after must be shorter than pause-after");
        }
    }

    /** The thresholds with the default notify-after of two minutes. */
    public MarketHealthProperties(
            boolean enabled, Duration reconnectingAfter, Duration pauseAfter, Duration stableBeforeRecover) {
        this(enabled, reconnectingAfter, pauseAfter, stableBeforeRecover, Duration.ofMinutes(2));
    }
}
