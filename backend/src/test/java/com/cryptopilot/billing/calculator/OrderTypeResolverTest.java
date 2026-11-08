package com.cryptopilot.billing.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.billing.model.HeldSubscription;
import com.cryptopilot.billing.model.enums.OrderType;
import com.cryptopilot.billing.model.enums.PlanTier;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** BR-55 and BR-63 as amended by D-146: renew or upgrade, never downgrade. */
class OrderTypeResolverTest {

    private static final Instant NOW = Instant.parse("2026-11-08T03:00:00Z");

    @ParameterizedTest(name = "nothing held, buying {0} -> NEW")
    @EnumSource(
            value = PlanTier.class,
            names = {"PRO", "PREMIUM"})
    void D146_withoutASubscription_anyPackageIsNew(PlanTier tier) {
        assertThat(OrderTypeResolver.resolve(null, tier, NOW)).contains(OrderType.NEW);
    }

    @ParameterizedTest(name = "holding {0}, buying {1} -> {2}")
    @CsvSource({"PRO, PRO, RENEW", "PREMIUM, PREMIUM, RENEW", "PRO, PREMIUM, UPGRADE"})
    void D146_theSameTierRenews_aHigherOneUpgrades(PlanTier held, PlanTier bought, OrderType expected) {
        assertThat(OrderTypeResolver.resolve(running(held), bought, NOW)).contains(expected);
    }

    @Test
    void D146_aLowerTier_isRefused() {
        assertThat(OrderTypeResolver.resolve(running(PlanTier.PREMIUM), PlanTier.PRO, NOW))
                .isEqualTo(Optional.empty());
    }

    /** At its end instant a subscription no longer runs, so even a lower tier is a fresh purchase. */
    @Test
    void D146_aSubscriptionAtItsEnd_countsAsNone() {
        HeldSubscription ending =
                new HeldSubscription(PlanTier.PREMIUM, NOW.minus(Duration.ofDays(30)), NOW, new BigDecimal("199000"));

        assertThat(OrderTypeResolver.resolve(ending, PlanTier.PRO, NOW)).contains(OrderType.NEW);
    }

    private static HeldSubscription running(PlanTier tier) {
        return new HeldSubscription(
                tier, NOW.minus(Duration.ofDays(5)), NOW.plus(Duration.ofDays(25)), new BigDecimal("99000"));
    }
}
