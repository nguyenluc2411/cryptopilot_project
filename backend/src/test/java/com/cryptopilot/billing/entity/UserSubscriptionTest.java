package com.cryptopilot.billing.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.cryptopilot.billing.PlanFixtures;
import com.cryptopilot.billing.calculator.CarryOverCalculator;
import com.cryptopilot.billing.model.HeldSubscription;
import com.cryptopilot.billing.model.PackageTerms;
import com.cryptopilot.billing.model.SubscriptionTerms;
import com.cryptopilot.billing.model.enums.GatewayCode;
import com.cryptopilot.billing.model.enums.OrderType;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.billing.model.enums.SubscriptionStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** BR-55 and the carry-over of D-147 on the subscription itself. */
class UserSubscriptionTest {

    private static final Instant NOW = Instant.parse("2026-11-08T03:00:00Z");
    private static final UUID TRADER = UUID.randomUUID();

    private static final SubscriptionPackage PRO_MONTHLY = SubscriptionPackage.paid(
            "PRO_MONTHLY", "Pro monthly", PlanTier.PRO, new BigDecimal("99000"), 30, PlanFixtures.PRO);
    private static final SubscriptionPackage PREMIUM_MONTHLY = SubscriptionPackage.paid(
            "PREMIUM_MONTHLY", "Premium monthly", PlanTier.PREMIUM, new BigDecimal("199000"), 30, PlanFixtures.PREMIUM);

    @Test
    void D147_aNewOrder_opensAnActiveSubscriptionWorthWhatWasPaid() {
        SubscriptionOrder order = paid(TRADER, PRO_MONTHLY, OrderType.NEW);
        SubscriptionTerms terms = CarryOverCalculator.terms(null, terms(PRO_MONTHLY), NOW);

        UserSubscription subscription = UserSubscription.open(order, terms, null);

        assertThat(subscription.getOrderId()).isEqualTo(order.getId());
        assertThat(subscription.getUserId()).isEqualTo(TRADER);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getStartAt()).isEqualTo(NOW);
        assertThat(subscription.getEndAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(subscription.getValueVnd()).isEqualByComparingTo("99000");
        assertThat(subscription.getCarriedValueVnd()).isZero();
        assertThat(subscription.getBonusDays()).isZero();
        assertThat(subscription.getCarriedFromId()).isNull();
    }

    /** The upgrade replaces the old subscription, points back at it and keeps the value it carried. */
    @Test
    void D147_anUpgrade_replacesTheOldSubscriptionAndCarriesItsValue() {
        UserSubscription pro = UserSubscription.open(
                paid(TRADER, PRO_MONTHLY, OrderType.NEW),
                CarryOverCalculator.terms(null, terms(PRO_MONTHLY), NOW.minus(Duration.ofDays(10))),
                null);
        HeldSubscription held = pro.held(PlanTier.PRO);
        SubscriptionTerms terms = CarryOverCalculator.terms(held, terms(PREMIUM_MONTHLY), NOW);

        pro.replace();
        UserSubscription premium = UserSubscription.open(paid(TRADER, PREMIUM_MONTHLY, OrderType.UPGRADE), terms, pro);

        assertThat(held.tier()).isEqualTo(PlanTier.PRO);
        assertThat(held.valueVnd()).isEqualByComparingTo("99000");
        assertThat(pro.getStatus()).isEqualTo(SubscriptionStatus.REPLACED);
        assertThat(premium.getCarriedFromId()).isEqualTo(pro.getId());
        assertThat(premium.getCarriedValueVnd()).isEqualByComparingTo("66000");
        assertThat(premium.getBonusDays()).isEqualTo(9);
        assertThat(premium.getValueVnd()).isEqualByComparingTo("265000");
    }

    @Test
    void D147_theReplacedSubscription_mustMatchTheOrderType() {
        SubscriptionOrder order = paid(TRADER, PRO_MONTHLY, OrderType.NEW);
        SubscriptionTerms newTerms = CarryOverCalculator.terms(null, terms(PRO_MONTHLY), NOW);
        UserSubscription other = UserSubscription.open(order, newTerms, null);
        SubscriptionTerms renewal = CarryOverCalculator.terms(other.held(PlanTier.PRO), terms(PRO_MONTHLY), NOW);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> UserSubscription.open(order, newTerms, other))
                .withMessageContaining("takes no subscription to replace");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UserSubscription.open(order, renewal, null))
                .withMessageContaining("needs subscription to replace");
    }

    @Test
    void D147_anotherTradersSubscription_cannotBeReplaced() {
        UserSubscription theirs = UserSubscription.open(
                paid(UUID.randomUUID(), PRO_MONTHLY, OrderType.NEW),
                CarryOverCalculator.terms(null, terms(PRO_MONTHLY), NOW),
                null);
        SubscriptionTerms renewal = CarryOverCalculator.terms(theirs.held(PlanTier.PRO), terms(PRO_MONTHLY), NOW);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> UserSubscription.open(paid(TRADER, PRO_MONTHLY, OrderType.RENEW), renewal, theirs))
                .withMessageContaining("another Trader");
    }

    @Test
    void BR53_anUnpaidOrder_opensNoSubscription() {
        SubscriptionOrder pending = SubscriptionOrder.create(
                TRADER, PRO_MONTHLY, OrderType.NEW, GatewayCode.SEPAY, "CP1", NOW, Duration.ofMinutes(15));

        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        UserSubscription.open(pending, CarryOverCalculator.terms(null, terms(PRO_MONTHLY), NOW), null))
                .withMessageContaining("not paid");
    }

    @Test
    void NSF14_aSubscription_expiresOnlyOnceItsPeriodIsOver() {
        UserSubscription subscription = UserSubscription.open(
                paid(TRADER, PRO_MONTHLY, OrderType.NEW),
                CarryOverCalculator.terms(null, terms(PRO_MONTHLY), NOW),
                null);
        Instant end = NOW.plus(Duration.ofDays(30));

        assertThatIllegalStateException()
                .isThrownBy(() -> subscription.expire(end.minusSeconds(1)))
                .withMessageContaining("runs until");
        subscription.expire(end);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThatIllegalStateException().isThrownBy(subscription::replace);
        assertThatIllegalStateException().isThrownBy(() -> subscription.expire(end));
    }

    @Test
    void NSF14_anActiveSubscription_isRemindedOnce_andAnEndedOneNever() {
        UserSubscription pro = UserSubscription.open(
                paid(TRADER, PRO_MONTHLY, OrderType.NEW),
                CarryOverCalculator.terms(null, terms(PRO_MONTHLY), NOW.minus(Duration.ofDays(28))),
                null);

        assertThat(pro.remind(NOW)).isTrue();
        assertThat(pro.getRemindedAt()).isEqualTo(NOW);
        assertThat(pro.remind(NOW.plusSeconds(3600))).isFalse();
        assertThat(pro.getRemindedAt()).isEqualTo(NOW);

        pro.expire(NOW.plus(Duration.ofDays(3)));
        assertThatIllegalStateException().isThrownBy(() -> pro.remind(NOW.plus(Duration.ofDays(3))));
    }

    @Test
    void D147_anExtension_addsDaysAndValue_toAnActiveSubscriptionOnly() {
        UserSubscription premium = UserSubscription.open(
                paid(TRADER, PREMIUM_MONTHLY, OrderType.NEW),
                CarryOverCalculator.terms(null, terms(PREMIUM_MONTHLY), NOW),
                null);
        Instant end = premium.getEndAt();
        BigDecimal value = premium.getValueVnd();

        premium.extend(new BigDecimal("99000"), 14);

        assertThat(premium.getEndAt()).isEqualTo(end.plus(Duration.ofDays(14)));
        assertThat(premium.getValueVnd()).isEqualByComparingTo(value.add(new BigDecimal("99000")));
        assertThatIllegalArgumentException().isThrownBy(() -> premium.extend(new BigDecimal("-1"), 1));
        premium.expire(premium.getEndAt());
        assertThatIllegalStateException().isThrownBy(() -> premium.extend(BigDecimal.ONE, 1));
    }

    private static SubscriptionOrder paid(UUID trader, SubscriptionPackage bought, OrderType type) {
        SubscriptionOrder order = SubscriptionOrder.create(
                trader, bought, type, GatewayCode.SEPAY, "CP" + UUID.randomUUID(), NOW, Duration.ofMinutes(15));
        order.markPaid("TX", "00", null, NOW);
        return order;
    }

    private static PackageTerms terms(SubscriptionPackage bought) {
        return new PackageTerms(bought.getTier(), bought.getPriceAmount(), bought.getDurationDays());
    }
}
