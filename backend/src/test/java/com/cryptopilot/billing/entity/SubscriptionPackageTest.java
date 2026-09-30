package com.cryptopilot.billing.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.billing.PlanEntitlements;
import com.cryptopilot.billing.model.enums.PlanTier;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The package invariants of BR-62 enforced before a row reaches the database: FREE priced 0, without duration and
 * not purchasable; a paid package of PRO or PREMIUM lasting 1–366 days at a price above 0; the rank
 * taken from the tier.
 *
 * <p>Rule: BR-62; SRS v1.1 entity 34, SRS 3.11.5; D-58.
 */
class SubscriptionPackageTest {

    private static final PlanEntitlements FREE_LIMITS =
            new PlanEntitlements(false, false, 3, 5, 3, false, false, false, false, 0, false, false);
    private static final PlanEntitlements PRO_LIMITS =
            new PlanEntitlements(true, true, null, 50, 20, true, true, true, true, 30, false, false);

    @Test
    void BR62_theFreePackage_isFreeForever_andNotForSale() {
        SubscriptionPackage free = SubscriptionPackage.free("FREE", "Free", FREE_LIMITS);

        assertThat(free.getTier()).isEqualTo(PlanTier.FREE);
        assertThat(free.getTierRank()).isZero();
        assertThat(free.getPriceAmount()).isZero();
        assertThat(free.getDurationDays()).isNull();
        assertThat(free.isPurchasable()).isFalse();
        assertThat(free.isActive()).isTrue();
        assertThat(free.getCurrency()).isEqualTo("VND");
        assertThat(free.entitlements()).isEqualTo(FREE_LIMITS);
    }

    @Test
    void BR62_aPaidPackage_isOnSale_withItsTiersRank_andKeepsEveryEntitlement() {
        SubscriptionPackage pro = SubscriptionPackage.paid(
                "PRO_MONTHLY", "Pro monthly", PlanTier.PRO, new BigDecimal("99000"), 30, PRO_LIMITS);

        assertThat(pro.getTierRank()).isEqualTo((short) 1);
        assertThat(pro.isPurchasable()).isTrue();
        assertThat(pro.getDurationDays()).isEqualTo(30);
        assertThat(pro.entitlements()).isEqualTo(PRO_LIMITS);
        assertThat(pro.getAiDailyQuota()).isEqualTo(30);
        assertThat(pro.getActivePlanMax()).isNull();
    }

    @Test
    void BR62_theRanks_orderTheTiers() {
        assertThat(PlanTier.FREE.rank()).isLessThan(PlanTier.PRO.rank());
        assertThat(PlanTier.PRO.rank()).isLessThan(PlanTier.PREMIUM.rank());
        assertThat(PlanTier.PREMIUM.rank()).isEqualTo(2);
    }

    @Test
    void BR62_freeIsNotSold() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        SubscriptionPackage.paid("FREE_PAID", "Free", PlanTier.FREE, BigDecimal.ZERO, 30, FREE_LIMITS));
    }

    @ParameterizedTest(name = "{0} days")
    @ValueSource(ints = {0, 367, -1})
    void BR62_aPaidDurationOutsideOneTo366_isRefused(int days) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubscriptionPackage.paid(
                        "PRO_X", "Pro", PlanTier.PRO, new BigDecimal("99000"), days, PRO_LIMITS));
    }

    @ParameterizedTest(name = "{0} days")
    @ValueSource(ints = {1, 366})
    void BR62_theDurationBounds_areAccepted(int days) {
        assertThat(SubscriptionPackage.paid("PRO_X", "Pro", PlanTier.PREMIUM, BigDecimal.ONE, days, PRO_LIMITS)
                        .getDurationDays())
                .isEqualTo(days);
    }

    /** SRS 3.11.5, BR-63: a paid package has a real price, so 0 is refused as well as a negative price. */
    @ParameterizedTest(name = "{0} VND")
    @ValueSource(strings = {"0", "-1"})
    void BR63_aPaidPackageNotPricedAboveZero_isRefused(String price) {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        SubscriptionPackage.paid("PRO_X", "Pro", PlanTier.PRO, new BigDecimal(price), 30, PRO_LIMITS))
                .withMessageContaining("above 0");
    }

    @Test
    void BR62_aCodeOrNameThatTheColumnsCannotHold_isRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> SubscriptionPackage.free(" ", "Free", FREE_LIMITS));
        assertThatIllegalArgumentException().isThrownBy(() -> SubscriptionPackage.free(null, "Free", FREE_LIMITS));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubscriptionPackage.free("X".repeat(33), "Free", FREE_LIMITS));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubscriptionPackage.free("FREE", "N".repeat(101), FREE_LIMITS));
    }

    @Test
    void BR62_missingArguments_areRefused() {
        assertThatNullPointerException().isThrownBy(() -> SubscriptionPackage.free("FREE", "Free", null));
        assertThatNullPointerException()
                .isThrownBy(() -> SubscriptionPackage.paid("P", "P", null, BigDecimal.ONE, 30, PRO_LIMITS));
        assertThatNullPointerException()
                .isThrownBy(() -> SubscriptionPackage.paid("P", "P", PlanTier.PRO, null, 30, PRO_LIMITS));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"activePlanMax", "watchlistMax", "activeAlertMax", "aiChatDaily"})
    void BR62_aNegativeMaximumOrQuota_isRefused(String which) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PlanEntitlements(
                        true,
                        true,
                        "activePlanMax".equals(which) ? -1 : null,
                        "watchlistMax".equals(which) ? -1 : 50,
                        "activeAlertMax".equals(which) ? -1 : 20,
                        true,
                        true,
                        true,
                        true,
                        "aiChatDaily".equals(which) ? -1 : 30,
                        false,
                        false))
                .withMessageContaining(which);
    }
}
