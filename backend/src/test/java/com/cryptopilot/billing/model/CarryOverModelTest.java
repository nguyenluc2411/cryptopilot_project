package com.cryptopilot.billing.model;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.billing.model.enums.PlanTier;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The inputs of the carry-over formula refuse what the schema would refuse. */
class CarryOverModelTest {

    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-12-01T00:00:00Z");

    @Test
    void D147_aHeldSubscription_endsAfterItStartsAndIsWorthZeroOrMore() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HeldSubscription(PlanTier.PRO, END, START, BigDecimal.ONE))
                .withMessageContaining("ends after it starts");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HeldSubscription(PlanTier.PRO, START, START, BigDecimal.ONE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HeldSubscription(PlanTier.PRO, START, END, new BigDecimal("-1")))
                .withMessageContaining("0 VND or more");
    }

    @Test
    void BR62_packageTerms_areThoseOfAPaidPackage() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PackageTerms(PlanTier.FREE, BigDecimal.ONE, 30))
                .withMessageContaining("never sold");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PackageTerms(PlanTier.PRO, BigDecimal.ZERO, 30))
                .withMessageContaining("above 0 VND");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PackageTerms(PlanTier.PRO, BigDecimal.ONE, 0))
                .withMessageContaining("at least a day");
    }
}
