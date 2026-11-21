package com.cryptopilot.market.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * When a market's exchange data counts as lost and when it counts as back (D-159), under
 * {@code cryptopilot.market.health}.
 *
 * <p>Rule: NSF-03; D-159.
 *
 * @param enabled whether the monitor watches the streams; off, a market is only closed by maintenance
 * @param reconnectingAfter how long without data before a market is reconnecting (no notice, stale rules apply)
 * @param pauseAfter how long without data before trading on the market is paused
 * @param stableBeforeRecover how long the data must flow without a break before a paused market starts recovering
 * @param notifyAfter how long an outage must last before the Traders are told of it; maintenance is told at once
 * @param recoveryTimeout how long a recovery may run before the administrators are told of it
 * @param maxOutageReplay how far back the engines replay an incident's data at most; a market whose data was lost
 *     longer does not reopen by itself
 * @param pauseRatio the share of a market's pairs whose connections may be silent while the market stays live (E5);
 *     more than that pauses it
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.market.health")
public record MarketHealthProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("10s") Duration reconnectingAfter,
        @NotNull @DefaultValue("30s") Duration pauseAfter,
        @NotNull @DefaultValue("15s") Duration stableBeforeRecover,
        @NotNull @DefaultValue("2m") Duration notifyAfter,
        @NotNull @DefaultValue("15m") Duration recoveryTimeout,
        @NotNull @DefaultValue("7d") Duration maxOutageReplay,
        @DefaultValue("0.5") double pauseRatio) {

    @ConstructorBinding
    public MarketHealthProperties {
        if (reconnectingAfter.compareTo(pauseAfter) >= 0) {
            throw new IllegalArgumentException("reconnecting-after must be shorter than pause-after");
        }
        if (stableBeforeRecover.compareTo(reconnectingAfter) <= 0) {
            throw new IllegalArgumentException("stable-before-recover must be longer than reconnecting-after");
        }
        if (pauseRatio < 0 || pauseRatio >= 1) {
            throw new IllegalArgumentException("pause-ratio must be at least 0 and below 1");
        }
    }

    /**
     * The thresholds with the defaults: notify-after two minutes, recovery-timeout fifteen minutes, max-outage-replay
     * seven days, pause-ratio a half.
     */
    public MarketHealthProperties(
            boolean enabled, Duration reconnectingAfter, Duration pauseAfter, Duration stableBeforeRecover) {
        this(
                enabled,
                reconnectingAfter,
                pauseAfter,
                stableBeforeRecover,
                Duration.ofMinutes(2),
                Duration.ofMinutes(15),
                Duration.ofDays(7),
                0.5);
    }
}
