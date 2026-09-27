package com.cryptopilot.billing;

import java.util.Objects;

/**
 * The plan a Trader has right now: the tier of their ACTIVE subscription, or FREE, and what that tier allows.
 *
 * <p>Rule: BR-62; SRS v1.1 UC-53; D-58.
 *
 * @param tier the tier
 * @param entitlements the entitlements of the tier's package
 */
public record EffectivePlan(PlanTier tier, PlanEntitlements entitlements) {

    public EffectivePlan {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(entitlements, "entitlements");
    }
}
