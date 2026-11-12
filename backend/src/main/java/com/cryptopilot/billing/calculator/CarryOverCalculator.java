package com.cryptopilot.billing.calculator;

import com.cryptopilot.billing.model.HeldSubscription;
import com.cryptopilot.billing.model.PackageTerms;
import com.cryptopilot.billing.model.SubscriptionTerms;
import com.cryptopilot.billing.model.enums.OrderType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The subscription a paid order creates, carrying over the unused value of the one it replaces (D-147).
 *
 * <p>What moves to the new subscription is the money not yet used, not the days, so an upgrade cannot be gamed by
 * buying a long cheap package and upgrading to a short expensive one:
 *
 * <pre>
 * remaining_value = value_vnd(old) * (end_old - now) / (end_old - start_old)    in seconds, rounded down to 1 VND
 * bonus_days      = floor(remaining_value * duration_new / price_new)          whole days, rounded down
 * NEW      end = paid_at + duration
 * RENEW    end = end_old + duration                                           (same tier: no conversion)
 * UPGRADE  end = paid_at + duration + bonus_days
 * value_vnd(new) = amount + remaining_value
 * </pre>
 *
 * The value of a subscription is read from what was paid for it, never from a package's current price (BR-56), so a
 * price change or several upgrades in a row still carry the right amount. A subscription worth 0 (given away) carries
 * nothing. Fractions of a VND and of a day belong to the system.
 *
 * <p>The formula runs when an order is quoted, to show the Trader, and again when it is paid, from the subscription
 * held then; the quote is never what activates it.
 *
 * <p>Rule: BR-55, BR-56, BR-63 as amended by D-147.
 */
public final class CarryOverCalculator {

    private CarryOverCalculator() {}

    /**
     * The subscription created by paying for {@code bought} at {@code paidAt}.
     *
     * @param held the Trader's ACTIVE subscription at {@code paidAt}, or {@code null}
     * @param bought the package as the order fixed it
     * @param paidAt when the gateway confirmed the payment
     * @throws IllegalStateException when {@code bought} is of a lower tier than {@code held}: such an order is never
     *     created, and one paid after the Trader upgraded through another order is flagged for an administrator instead
     */
    public static SubscriptionTerms terms(HeldSubscription held, PackageTerms bought, Instant paidAt) {
        Objects.requireNonNull(bought, "bought must not be null");
        Objects.requireNonNull(paidAt, "paidAt must not be null");
        OrderType type = OrderTypeResolver.resolve(held, bought.tier(), paidAt)
                .orElseThrow(() -> new IllegalStateException("a " + bought.tier()
                        + " package neither renews nor upgrades a " + held.tier() + " subscription"));
        Duration period = Duration.ofDays(bought.durationDays());
        return switch (type) {
            case NEW -> new SubscriptionTerms(type, paidAt, paidAt.plus(period), bought.price(), BigDecimal.ZERO, 0);
            case RENEW -> {
                BigDecimal carried = remainingValue(held, paidAt);
                yield new SubscriptionTerms(
                        type, paidAt, held.endAt().plus(period), bought.price().add(carried), carried, 0);
            }
            case UPGRADE -> {
                BigDecimal carried = remainingValue(held, paidAt);
                int bonus = bonusDays(carried, bought);
                yield new SubscriptionTerms(
                        type,
                        paidAt,
                        paidAt.plus(period).plus(Duration.ofDays(bonus)),
                        bought.price().add(carried),
                        carried,
                        bonus);
            }
        };
    }

    /**
     * The part of {@code held}'s value not used by {@code now}, in proportion to the time left and rounded down to a
     * whole VND: the full value before it starts, 0 once it has ended.
     */
    public static BigDecimal remainingValue(HeldSubscription held, Instant now) {
        Objects.requireNonNull(held, "held must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (!now.isBefore(held.endAt())) {
            return BigDecimal.ZERO;
        }
        Instant from = now.isAfter(held.startAt()) ? now : held.startAt();
        long left = Duration.between(from, held.endAt()).toSeconds();
        long total = Duration.between(held.startAt(), held.endAt()).toSeconds();
        return held.valueVnd()
                .multiply(BigDecimal.valueOf(left))
                .divide(BigDecimal.valueOf(total), 0, RoundingMode.DOWN);
    }

    /** The whole days of {@code bought} that {@code value} pays for at its daily price, rounded down. */
    public static int bonusDays(BigDecimal value, PackageTerms bought) {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(bought, "bought must not be null");
        if (value.signum() <= 0) {
            return 0;
        }
        return value.multiply(BigDecimal.valueOf(bought.durationDays()))
                .divide(bought.price(), 0, RoundingMode.DOWN)
                .intValueExact();
    }

    /**
     * The whole days of the held subscription that {@code amount} buys at the price the Trader paid for it: a payment
     * for a lower plan that arrived after the Trader moved up is turned into time of the plan held (D-147). A
     * subscription that cost nothing is valued at the package it was bought from, {@code fallback}.
     */
    public static int convertedDays(BigDecimal amount, HeldSubscription held, PackageTerms fallback) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(held, "held must not be null");
        if (amount.signum() <= 0) {
            return 0;
        }
        if (held.valueVnd().signum() <= 0) {
            return bonusDays(amount, Objects.requireNonNull(fallback, "fallback must not be null"));
        }
        long seconds = Duration.between(held.startAt(), held.endAt()).toSeconds();
        return amount.multiply(BigDecimal.valueOf(seconds))
                .divide(held.valueVnd().multiply(BigDecimal.valueOf(86_400)), 0, RoundingMode.DOWN)
                .intValueExact();
    }
}
