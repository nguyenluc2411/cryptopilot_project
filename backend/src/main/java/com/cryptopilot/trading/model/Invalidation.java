package com.cryptopilot.trading.model;

import com.cryptopilot.trading.model.enums.InvalidationReason;
import java.time.Instant;
import java.util.Objects;

/**
 * A waiting plan the matching engine has decided to invalidate: an invalidation level was reached before its entry.
 *
 * <p>Rule: BR-68, NSF-07; D-78 (the time is the open time of the candle that reached the level); D-111 (M3).
 *
 * @param entry the plan as the engine tracked it
 * @param reason which level was reached
 * @param invalidatedAt when the plan counts as invalidated
 */
public record Invalidation(TrackedEntry entry, InvalidationReason reason, Instant invalidatedAt)
        implements PlanOutcome {

    public Invalidation {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(invalidatedAt, "invalidatedAt");
    }

    @Override
    public Instant at() {
        return invalidatedAt;
    }
}
