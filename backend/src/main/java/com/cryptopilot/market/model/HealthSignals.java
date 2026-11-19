package com.cryptopilot.market.model;

import java.time.Instant;
import java.util.Objects;

/**
 * What the health monitor knows about one market at one instant (D-158).
 *
 * @param lastLiveAt when the market's latest live data arrived (the start of the process if none came yet)
 * @param liveSince when the current unbroken run of live data began; the same as {@code lastLiveAt} after a break
 * @param restAvailable whether the exchange's REST API answered lately (not refused, banned or behind an open circuit)
 * @param maintenance whether an administrator holds the market closed
 * @param recoveryDone whether every pair with orders, positions or plans has replayed up to now and the timed work
 *     due meanwhile has run
 */
public record HealthSignals(
        Instant lastLiveAt, Instant liveSince, boolean restAvailable, boolean maintenance, boolean recoveryDone) {

    public HealthSignals {
        Objects.requireNonNull(lastLiveAt, "lastLiveAt");
        Objects.requireNonNull(liveSince, "liveSince");
    }
}
