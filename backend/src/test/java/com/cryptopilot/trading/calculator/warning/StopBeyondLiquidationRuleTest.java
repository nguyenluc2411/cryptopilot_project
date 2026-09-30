package com.cryptopilot.trading.calculator.warning;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.enums.WarningSeverity;
import com.cryptopilot.trading.model.enums.WarningType;
import org.junit.jupiter.api.Test;

/**
 * SL_BEYOND_LIQUIDATION around the liquidation price T-036 estimates. At 20x in bracket 1 (MMR 0.004, no maintenance
 * amount) the price does not depend on the quantity: LONG 100 × 0.95 / 0.996 → 95.39, SHORT 100 × 1.05 / 1.004 →
 * 104.58.
 *
 * <p>Rule: BR-28; A-01.
 */
class StopBeyondLiquidationRuleTest {

    private final WarningRule rule = new StopBeyondLiquidationRule();

    @Test
    void BR28_longStopAtTheLiquidationPrice_isBlocking() {
        assertThat(rule.evaluate(
                        PlanFixture.futuresLong().leverage(20).stop("95.39").build()))
                .hasValueSatisfying(w -> {
                    assertThat(w.type()).isEqualTo(WarningType.SL_BEYOND_LIQUIDATION);
                    assertThat(w.severity()).isEqualTo(WarningSeverity.BLOCKING);
                    assertThat(w.message()).contains("95.39");
                });
    }

    @Test
    void BR28_longStopOneTickAboveTheLiquidationPrice_raisesNothing() {
        assertThat(rule.evaluate(
                        PlanFixture.futuresLong().leverage(20).stop("95.40").build()))
                .isEmpty();
    }

    @Test
    void BR28_longStopOneTickBelowTheLiquidationPrice_isBlocking() {
        assertThat(rule.evaluate(
                        PlanFixture.futuresLong().leverage(20).stop("95.38").build()))
                .isPresent();
    }

    @Test
    void BR28_shortStopAtTheLiquidationPrice_isBlocking() {
        assertThat(rule.evaluate(
                        PlanFixture.futuresShort().leverage(20).stop("104.58").build()))
                .hasValueSatisfying(w -> assertThat(w.severity()).isEqualTo(WarningSeverity.BLOCKING));
    }

    @Test
    void BR28_shortStopOneTickBelowTheLiquidationPrice_raisesNothing() {
        assertThat(rule.evaluate(
                        PlanFixture.futuresShort().leverage(20).stop("104.57").build()))
                .isEmpty();
    }

    @Test
    void BR28_shortStopOneTickAboveTheLiquidationPrice_isBlocking() {
        assertThat(rule.evaluate(
                        PlanFixture.futuresShort().leverage(20).stop("104.59").build()))
                .isPresent();
    }

    /** 1x in bracket 2 (maintenance amount 50): the estimate is 0, which no stop loss can be at or below. */
    @Test
    void BR28_aLongThePriceCannotLiquidate_raisesNothing() {
        PlanCalculation plan =
                PlanFixture.futuresLong().leverage(1).capital("250000").build();

        assertThat(plan.liquidation().bracket().bracketNo()).isEqualTo(2);
        assertThat(plan.liquidation().liquidationPrice()).isZero();
        assertThat(rule.evaluate(plan)).isEmpty();
    }

    @Test
    void BR28_aSpotPlan_raisesNothing() {
        assertThat(rule.evaluate(PlanFixture.spot().build())).isEmpty();
    }
}
