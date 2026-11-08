package com.cryptopilot.billing.calculator;

import com.cryptopilot.billing.model.HeldSubscription;
import com.cryptopilot.billing.model.enums.OrderType;
import com.cryptopilot.billing.model.enums.PlanTier;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides what buying a package does to the Trader's subscription: NEW without a running ACTIVE subscription, RENEW
 * for the same tier, UPGRADE for a higher one. A lower tier is no order at all (the Trader may only renew or upgrade
 * while a paid subscription runs) and comes back empty.
 *
 * <p>A subscription still marked ACTIVE whose period has ended counts as none: the expiry job may not have run yet.
 *
 * <p>Rule: BR-55 and BR-63 as amended by D-146.
 */
public final class OrderTypeResolver {

    private OrderTypeResolver() {}

    /**
     * The type of an order for a package of {@code tier}, or empty when that tier is lower than the one held.
     *
     * @param held the Trader's ACTIVE subscription, or {@code null}
     * @param tier the tier of the package bought, PRO or PREMIUM
     * @param now the instant the question is asked: when the order is quoted, created or paid
     */
    public static Optional<OrderType> resolve(HeldSubscription held, PlanTier tier, Instant now) {
        Objects.requireNonNull(tier, "tier must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (held == null || !held.endAt().isAfter(now)) {
            return Optional.of(OrderType.NEW);
        }
        int step = Integer.compare(tier.rank(), held.tier().rank());
        if (step == 0) {
            return Optional.of(OrderType.RENEW);
        }
        return step > 0 ? Optional.of(OrderType.UPGRADE) : Optional.empty();
    }
}
