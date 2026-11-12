package com.cryptopilot.billing.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.cryptopilot.billing.model.HeldSubscription;
import com.cryptopilot.billing.model.PackageTerms;
import com.cryptopilot.billing.model.SubscriptionTerms;
import com.cryptopilot.billing.model.enums.OrderType;
import com.cryptopilot.billing.model.enums.PlanTier;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The carry-over formula of D-147, with the prices V11 seeds. */
class CarryOverCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-11-08T03:00:00Z");

    private static final PackageTerms PRO_MONTHLY = terms(PlanTier.PRO, "99000", 30);
    private static final PackageTerms PRO_YEARLY = terms(PlanTier.PRO, "990000", 365);
    private static final PackageTerms PREMIUM_MONTHLY = terms(PlanTier.PREMIUM, "199000", 30);
    private static final PackageTerms PREMIUM_YEARLY = terms(PlanTier.PREMIUM, "1990000", 365);

    /** The four examples agreed with the user (handoff 3.2): the days a PRO subscription buys of PREMIUM. */
    @ParameterizedTest(name = "{0} days left of {1} -> {2}: carries {3}, +{4} days, {5} days in all")
    @CsvSource({
        "20, PRO_MONTHLY, PREMIUM_MONTHLY,  66000,   9,  39",
        "20, PRO_MONTHLY, PREMIUM_YEARLY,   66000,  12, 377",
        "365, PRO_YEARLY, PREMIUM_MONTHLY, 990000, 149, 179",
        "365, PRO_YEARLY, PREMIUM_YEARLY,  990000, 181, 546"
    })
    void D147_anUpgrade_paysTheUnusedValueBackInDaysOfTheNewTier(
            int daysLeft, String held, String bought, String carried, int bonusDays, int totalDays) {
        PackageTerms heldPackage = named(held);
        HeldSubscription subscription = heldFor(heldPackage, daysLeft, heldPackage.price());

        SubscriptionTerms terms = CarryOverCalculator.terms(subscription, named(bought), NOW);

        assertThat(terms.orderType()).isEqualTo(OrderType.UPGRADE);
        assertThat(terms.carriedValueVnd()).isEqualByComparingTo(carried);
        assertThat(terms.bonusDays()).isEqualTo(bonusDays);
        assertThat(terms.startAt()).isEqualTo(NOW);
        assertThat(terms.endAt()).isEqualTo(NOW.plus(Duration.ofDays(totalDays)));
        assertThat(terms.valueVnd()).isEqualByComparingTo(named(bought).price().add(new BigDecimal(carried)));
    }

    @Test
    void D147_withoutASubscription_theOrderIsNewAndStartsWhenPaid() {
        SubscriptionTerms terms = CarryOverCalculator.terms(null, PRO_MONTHLY, NOW);

        assertThat(terms)
                .isEqualTo(new SubscriptionTerms(
                        OrderType.NEW,
                        NOW,
                        NOW.plus(Duration.ofDays(30)),
                        new BigDecimal("99000"),
                        BigDecimal.ZERO,
                        0));
    }

    /** The expiry job may not have run yet: an ACTIVE row past its end is no subscription. */
    @Test
    void D147_aSubscriptionPastItsEnd_isNoSubscription() {
        HeldSubscription ended =
                new HeldSubscription(PlanTier.PREMIUM, NOW.minus(Duration.ofDays(30)), NOW, new BigDecimal("199000"));

        SubscriptionTerms terms = CarryOverCalculator.terms(ended, PRO_MONTHLY, NOW);

        assertThat(terms.orderType()).isEqualTo(OrderType.NEW);
        assertThat(terms.carriedValueVnd()).isZero();
    }

    /** A renewal adds the new period after the current end, and keeps the value for the next upgrade. */
    @Test
    void D147_aRenewal_addsThePeriodAfterTheCurrentEndWithoutBonusDays() {
        HeldSubscription held = heldFor(PRO_MONTHLY, 20, PRO_MONTHLY.price());

        SubscriptionTerms terms = CarryOverCalculator.terms(held, PRO_YEARLY, NOW);

        assertThat(terms.orderType()).isEqualTo(OrderType.RENEW);
        assertThat(terms.startAt()).isEqualTo(NOW);
        assertThat(terms.endAt()).isEqualTo(held.endAt().plus(Duration.ofDays(365)));
        assertThat(terms.bonusDays()).isZero();
        assertThat(terms.carriedValueVnd()).isEqualByComparingTo("66000");
        assertThat(terms.valueVnd()).isEqualByComparingTo("1056000");
    }

    /**
     * Two steps: PRO_MONTHLY with 20 days left upgraded to PREMIUM_MONTHLY (39 days, worth 265.000), renewed 10 days
     * later with 29 of 39 days left: 265.000 x 29/39 = 197.051 is carried, and the next upgrade would read it from
     * value_vnd rather than from any price.
     */
    @Test
    void D147_anUpgradeThenARenewal_carriesTheValueOfTheWholeChain() {
        SubscriptionTerms upgraded =
                CarryOverCalculator.terms(heldFor(PRO_MONTHLY, 20, PRO_MONTHLY.price()), PREMIUM_MONTHLY, NOW);
        HeldSubscription premium =
                new HeldSubscription(PlanTier.PREMIUM, upgraded.startAt(), upgraded.endAt(), upgraded.valueVnd());
        Instant later = NOW.plus(Duration.ofDays(10));

        SubscriptionTerms renewed = CarryOverCalculator.terms(premium, PREMIUM_MONTHLY, later);

        assertThat(upgraded.valueVnd()).isEqualByComparingTo("265000");
        assertThat(renewed.orderType()).isEqualTo(OrderType.RENEW);
        assertThat(renewed.carriedValueVnd()).isEqualByComparingTo("197051");
        assertThat(renewed.endAt()).isEqualTo(upgraded.endAt().plus(Duration.ofDays(30)));
        assertThat(renewed.valueVnd()).isEqualByComparingTo("396051");
    }

    /** BR-56: the value comes from what was paid, so a later price change does not move the result. */
    @Test
    void BR56_theCarriedValue_comesFromWhatWasPaidNotFromTheCurrentPrice() {
        HeldSubscription boughtAtTheOldPrice = heldFor(PRO_MONTHLY, 15, new BigDecimal("80000"));

        SubscriptionTerms terms = CarryOverCalculator.terms(boughtAtTheOldPrice, PREMIUM_MONTHLY, NOW);

        assertThat(terms.carriedValueVnd()).isEqualByComparingTo("40000");
        assertThat(terms.bonusDays()).isEqualTo(6);
    }

    @Test
    void D147_aSubscriptionGivenAway_carriesNothing() {
        SubscriptionTerms terms =
                CarryOverCalculator.terms(heldFor(PRO_YEARLY, 300, BigDecimal.ZERO), PREMIUM_MONTHLY, NOW);

        assertThat(terms.orderType()).isEqualTo(OrderType.UPGRADE);
        assertThat(terms.carriedValueVnd()).isZero();
        assertThat(terms.bonusDays()).isZero();
        assertThat(terms.endAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    }

    @Test
    void D147_aLowerTier_isNeitherARenewalNorAnUpgrade() {
        HeldSubscription premium = heldFor(PREMIUM_MONTHLY, 10, PREMIUM_MONTHLY.price());

        assertThatIllegalStateException()
                .isThrownBy(() -> CarryOverCalculator.terms(premium, PRO_YEARLY, NOW))
                .withMessageContaining("PRO package neither renews nor upgrades a PREMIUM subscription");
    }

    /** Seconds count, and the fraction of a VND is dropped. */
    @Test
    void D147_theRemainingValue_isProportionalToTheSecondsLeftAndRoundedDown() {
        HeldSubscription held = new HeldSubscription(
                PlanTier.PRO, NOW.minus(Duration.ofSeconds(1)), NOW.plus(Duration.ofSeconds(2)), new BigDecimal("100"));

        assertThat(CarryOverCalculator.remainingValue(held, NOW)).isEqualByComparingTo("66");
    }

    @Test
    void D147_aSubscriptionNotStartedYet_isWorthItsWholeValue() {
        HeldSubscription later = new HeldSubscription(
                PlanTier.PRO, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(31)), new BigDecimal("99000"));

        assertThat(CarryOverCalculator.remainingValue(later, NOW)).isEqualByComparingTo("99000");
    }

    @Test
    void D147_aSubscriptionThatHasEnded_isWorthNothing() {
        HeldSubscription ended = heldFor(PRO_MONTHLY, 0, PRO_MONTHLY.price());

        assertThat(CarryOverCalculator.remainingValue(ended, NOW)).isZero();
    }

    @Test
    void D147_lessThanADaysWorth_buysNoDay() {
        assertThat(CarryOverCalculator.bonusDays(new BigDecimal("6633"), PREMIUM_MONTHLY))
                .isZero();
        assertThat(CarryOverCalculator.bonusDays(new BigDecimal("6634"), PREMIUM_MONTHLY))
                .isEqualTo(1);
        assertThat(CarryOverCalculator.bonusDays(BigDecimal.ZERO, PREMIUM_MONTHLY))
                .isZero();
    }

    @Test
    void D147_aLowerPlansPayment_buysDaysAtThePricePaidForThePlanHeld() {
        HeldSubscription premium = heldFor(PREMIUM_MONTHLY, 20, new BigDecimal("199000"));

        // 990,000 VND at 199,000 VND for 30 days: 149.2 days, rounded down.
        assertThat(CarryOverCalculator.convertedDays(new BigDecimal("990000"), premium, PREMIUM_MONTHLY))
                .isEqualTo(149);
        assertThat(CarryOverCalculator.convertedDays(BigDecimal.ZERO, premium, PREMIUM_MONTHLY))
                .isZero();
    }

    @Test
    void D147_aSubscriptionThatCostNothing_isValuedAtItsPackage() {
        HeldSubscription gift = heldFor(PREMIUM_MONTHLY, 20, BigDecimal.ZERO);

        assertThat(CarryOverCalculator.convertedDays(new BigDecimal("99000"), gift, PREMIUM_MONTHLY))
                .isEqualTo(14);
    }

    /** A subscription of {@code bought} with {@code daysLeft} days of its period left at {@link #NOW}. */
    private static HeldSubscription heldFor(PackageTerms bought, int daysLeft, BigDecimal value) {
        Instant end = NOW.plus(Duration.ofDays(daysLeft));
        return new HeldSubscription(bought.tier(), end.minus(Duration.ofDays(bought.durationDays())), end, value);
    }

    private static PackageTerms terms(PlanTier tier, String price, int days) {
        return new PackageTerms(tier, new BigDecimal(price), days);
    }

    private static PackageTerms named(String code) {
        return switch (code) {
            case "PRO_MONTHLY" -> PRO_MONTHLY;
            case "PRO_YEARLY" -> PRO_YEARLY;
            case "PREMIUM_MONTHLY" -> PREMIUM_MONTHLY;
            case "PREMIUM_YEARLY" -> PREMIUM_YEARLY;
            default -> throw new IllegalArgumentException(code);
        };
    }
}
