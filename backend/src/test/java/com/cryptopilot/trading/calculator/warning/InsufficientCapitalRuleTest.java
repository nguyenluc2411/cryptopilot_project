package com.cryptopilot.trading.calculator.warning;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.trading.WarningSeverity;
import com.cryptopilot.trading.WarningType;
import com.cryptopilot.trading.model.PlanWarning;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * INSUFFICIENT_CAPITAL at the capital boundary. With E 100, capital 1,000 and risk 1 % the budget is 10, so a stop
 * distance of 1 buys exactly 10 (notional 1,000): 0.99 buys 10.101 (1,010.1) and 1.01 buys 9.900 (990).
 *
 * <p>Rule: BR-25, BR-26.
 */
class InsufficientCapitalRuleTest {

    private final WarningRule rule = new InsufficientCapitalRule();

    @Test
    void BR25_spotNotionalEqualToCapital_raisesNothing() {
        assertThat(rule.evaluate(PlanFixture.spot().stop("99").build())).isEmpty();
    }

    @Test
    void BR25_spotNotionalJustAboveCapital_isBlocking() {
        Optional<PlanWarning> warning =
                rule.evaluate(PlanFixture.spot().stop("99.01").build());

        assertThat(warning).hasValueSatisfying(w -> {
            assertThat(w.type()).isEqualTo(WarningType.INSUFFICIENT_CAPITAL);
            assertThat(w.severity()).isEqualTo(WarningSeverity.BLOCKING);
            assertThat(w.message()).contains("notional 1010.1").contains("capital 1000");
        });
    }

    @Test
    void BR25_spotNotionalJustBelowCapital_raisesNothing() {
        assertThat(rule.evaluate(PlanFixture.spot().stop("98.99").build())).isEmpty();
    }

    @Test
    void BR26_futuresMarginEqualToCapital_raisesNothing() {
        assertThat(rule.evaluate(
                        PlanFixture.futuresLong().leverage(1).stop("99").build()))
                .isEmpty();
    }

    @Test
    void BR26_futuresMarginJustAboveCapital_isBlocking() {
        Optional<PlanWarning> warning = rule.evaluate(
                PlanFixture.futuresLong().leverage(1).stop("99.01").build());

        assertThat(warning).hasValueSatisfying(w -> {
            assertThat(w.severity()).isEqualTo(WarningSeverity.BLOCKING);
            assertThat(w.message()).contains("initial margin 1010.1");
        });
    }

    @Test
    void BR26_futuresMarginJustBelowCapital_raisesNothing() {
        assertThat(rule.evaluate(
                        PlanFixture.futuresLong().leverage(1).stop("98.99").build()))
                .isEmpty();
    }

    /** The same notional of 1,010.1 needs a margin of only 505.05 at 2x: Futures compares the margin, not N. */
    @Test
    void BR26_futuresNotionalAboveCapitalWithMarginBelow_raisesNothing() {
        assertThat(rule.evaluate(
                        PlanFixture.futuresLong().leverage(2).stop("99.01").build()))
                .isEmpty();
    }
}
