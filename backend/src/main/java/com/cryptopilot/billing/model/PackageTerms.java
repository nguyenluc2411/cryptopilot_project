package com.cryptopilot.billing.model;

import com.cryptopilot.billing.model.enums.PlanTier;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The terms of a paid package as an order fixed them when it was created (BR-56): a later change of the package's
 * price never reaches an order already created.
 *
 * @param tier PRO or PREMIUM
 * @param price the list price in VND, above 0, which is also the amount charged
 * @param durationDays 1–366
 */
public record PackageTerms(PlanTier tier, BigDecimal price, int durationDays) {

    public PackageTerms {
        Objects.requireNonNull(tier, "tier must not be null");
        Objects.requireNonNull(price, "price must not be null");
        if (tier == PlanTier.FREE) {
            throw new IllegalArgumentException("the FREE package is never sold");
        }
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("a paid package is priced above 0 VND, was " + price);
        }
        if (durationDays < 1) {
            throw new IllegalArgumentException("a paid package lasts at least a day, was " + durationDays);
        }
    }
}
