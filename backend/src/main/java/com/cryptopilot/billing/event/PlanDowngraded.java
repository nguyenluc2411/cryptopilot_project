package com.cryptopilot.billing.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The Trader's plan dropped to a lower tier: the subscription ended and the plan held now ranks lower (BR-64). The
 * modules that keep plan-limited items (alerts) bring them within the new plan; the Trader is told with MSG49.
 *
 * <p>Tiers are names ({@code FREE}, {@code PRO}, {@code PREMIUM}) so a listener needs no billing type.
 *
 * @param userId the Trader
 * @param fromTier the tier held until now
 * @param toTier the tier held from now on
 * @param toPlanName the display name of the plan held from now on, for MSG49
 * @param at when the plan dropped
 */
public record PlanDowngraded(UUID userId, String fromTier, String toTier, String toPlanName, Instant at) {

    public PlanDowngraded {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(fromTier, "fromTier must not be null");
        Objects.requireNonNull(toTier, "toTier must not be null");
        Objects.requireNonNull(toPlanName, "toPlanName must not be null");
        Objects.requireNonNull(at, "at must not be null");
    }
}
