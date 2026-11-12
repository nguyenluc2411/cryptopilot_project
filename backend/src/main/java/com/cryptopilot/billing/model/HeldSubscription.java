package com.cryptopilot.billing.model;

import com.cryptopilot.billing.model.enums.PlanTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The ACTIVE subscription a Trader holds, as the carry-over formula reads it.
 *
 * @param tier the tier of its package
 * @param startAt when its period started
 * @param endAt when its period ends, after {@code startAt}
 * @param valueVnd what the whole period is worth: the amount paid for it plus the value it carried in (D-147)
 */
public record HeldSubscription(PlanTier tier, Instant startAt, Instant endAt, BigDecimal valueVnd) {

    public HeldSubscription {
        Objects.requireNonNull(tier, "tier must not be null");
        Objects.requireNonNull(startAt, "startAt must not be null");
        Objects.requireNonNull(endAt, "endAt must not be null");
        Objects.requireNonNull(valueVnd, "valueVnd must not be null");
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("a subscription ends after it starts, was " + startAt + " → " + endAt);
        }
        if (valueVnd.signum() < 0) {
            throw new IllegalArgumentException("a subscription is worth 0 VND or more, was " + valueVnd);
        }
    }
}
