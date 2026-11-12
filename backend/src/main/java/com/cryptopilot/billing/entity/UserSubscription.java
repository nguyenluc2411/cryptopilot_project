package com.cryptopilot.billing.entity;

import com.cryptopilot.billing.model.HeldSubscription;
import com.cryptopilot.billing.model.SubscriptionTerms;
import com.cryptopilot.billing.model.enums.OrderType;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.billing.model.enums.SubscriptionStatus;
import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * The subscription a paid order created: the period the Trader holds the order's package for.
 *
 * <p>A Trader holds at most one ACTIVE subscription ({@code uq_user_subscription_one_active}). A renewal or an upgrade
 * marks the one it replaces REPLACED and points back at it through {@code carried_from_id}, with the value it carried
 * over and the bonus days that value bought (D-147). {@code value_vnd} is what the whole period is worth, which the
 * next upgrade reads instead of any package's current price (BR-56).
 *
 * <p>Rule: BR-55, BR-56, BR-63 as amended by D-147; SRS v1.1 entity 36; D-58.
 */
@Getter
@Entity
@Table(name = "user_subscription")
@AttributeOverride(name = "id", column = @Column(name = "subscription_id", nullable = false, updatable = false))
public class UserSubscription extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "start_at", nullable = false, updatable = false)
    private Instant startAt;

    /** Moves later only when a payment is turned into days of this subscription ({@link #extend}). */
    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_status", nullable = false, length = 32)
    private SubscriptionStatus status;

    /** Grows with {@link #extend}, so the value of a day stays what the Trader paid for it. */
    @Column(name = "value_vnd", nullable = false, precision = 18, scale = 0)
    private BigDecimal valueVnd;

    @Column(name = "carried_value_vnd", nullable = false, precision = 18, scale = 0, updatable = false)
    private BigDecimal carriedValueVnd;

    @Column(name = "bonus_days", nullable = false, updatable = false)
    private int bonusDays;

    /** When the Trader was told this subscription ends within 3 days (NSF-14), or {@code null}. */
    @Column(name = "reminded_at")
    private Instant remindedAt;

    /** The subscription this one replaced, or {@code null} for a NEW order. */
    @Column(name = "carried_from_id", updatable = false)
    private UUID carriedFromId;

    /** For JPA only. */
    protected UserSubscription() {}

    private UserSubscription(SubscriptionOrder order, SubscriptionTerms terms, UUID carriedFromId) {
        this.orderId = order.getId();
        this.userId = order.getUserId();
        this.startAt = terms.startAt();
        this.endAt = terms.endAt();
        this.status = SubscriptionStatus.ACTIVE;
        this.valueVnd = terms.valueVnd();
        this.carriedValueVnd = terms.carriedValueVnd();
        this.bonusDays = terms.bonusDays();
        this.carriedFromId = carriedFromId;
    }

    /**
     * The ACTIVE subscription a paid order creates.
     *
     * @param replaced the subscription a RENEW or an UPGRADE replaces (mark it {@link #replace() replaced} and flush
     *     it first), or {@code null} for NEW
     * @throws IllegalArgumentException when the order is not PAID, or {@code replaced} does not match the order type
     */
    public static UserSubscription open(SubscriptionOrder order, SubscriptionTerms terms, UserSubscription replaced) {
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(terms, "terms must not be null");
        if (order.getPaidAt() == null) {
            throw new IllegalArgumentException("order " + order.getOrderCode() + " is not paid");
        }
        if ((terms.orderType() == OrderType.NEW) != (replaced == null)) {
            throw new IllegalArgumentException("a " + terms.orderType() + " order "
                    + (replaced == null ? "needs" : "takes no") + " subscription to replace");
        }
        if (replaced != null && !replaced.getUserId().equals(order.getUserId())) {
            throw new IllegalArgumentException("the subscription replaced belongs to another Trader");
        }
        return new UserSubscription(order, terms, replaced == null ? null : replaced.getId());
    }

    /** The subscription as the carry-over formula reads it, for a package of {@code tier}. */
    public HeldSubscription held(PlanTier tier) {
        return new HeldSubscription(tier, startAt, endAt, valueVnd);
    }

    /**
     * Marks the subscription replaced by a renewal or an upgrade.
     *
     * @throws IllegalStateException when it is not ACTIVE
     */
    public void replace() {
        requireActive("be replaced");
        this.status = SubscriptionStatus.REPLACED;
    }

    /**
     * Adds {@code days} to the end of this subscription, bought with {@code amount}: a payment for a lower plan that
     * arrived after the Trader moved up, turned into time of the plan held (D-147).
     *
     * @throws IllegalStateException when the subscription is not ACTIVE
     */
    public void extend(BigDecimal amount, int days) {
        Objects.requireNonNull(amount, "amount must not be null");
        requireActive("be extended");
        if (amount.signum() < 0 || days < 0) {
            throw new IllegalArgumentException("an extension adds value and days, was " + amount + ", " + days);
        }
        this.endAt = endAt.plus(Duration.ofDays(days));
        this.valueVnd = valueVnd.add(amount);
    }

    /**
     * Records that the Trader was told this subscription ends soon (NSF-14), once.
     *
     * @return whether this call recorded it, {@code false} when the Trader was told already
     * @throws IllegalStateException when the subscription is not ACTIVE
     */
    public boolean remind(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        requireActive("remind");
        if (remindedAt != null) {
            return false;
        }
        this.remindedAt = now;
        return true;
    }

    /**
     * Ends a subscription whose period is over (NSF-14).
     *
     * @throws IllegalStateException when it is not ACTIVE or still runs at {@code now}
     */
    public void expire(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        requireActive("expire");
        if (now.isBefore(endAt)) {
            throw new IllegalStateException("subscription " + getId() + " runs until " + endAt);
        }
        this.status = SubscriptionStatus.EXPIRED;
    }

    private void requireActive(String action) {
        if (status != SubscriptionStatus.ACTIVE) {
            throw new IllegalStateException("subscription " + getId() + " is " + status + ", it cannot " + action);
        }
    }
}
