package com.cryptopilot.watchlist.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The Trader's alerts were brought within a lower plan (BR-64): those the plan no longer allows were paused. Published
 * after every plan drop, with {@code paused} 0 when nothing had to be paused, so the Trader is told MSG49 either way.
 *
 * @param userId the Trader
 * @param planName the display name of the plan held now
 * @param paused how many ACTIVE alerts were paused
 * @param at when the plan dropped
 */
public record AlertsPausedForPlan(UUID userId, String planName, int paused, Instant at) {

    public AlertsPausedForPlan {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(planName, "planName must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (paused < 0) {
            throw new IllegalArgumentException("paused is a count, was " + paused);
        }
    }
}
