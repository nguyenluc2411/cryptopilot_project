package com.cryptopilot.trading.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.tuple;

import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.Direction;
import com.cryptopilot.trading.model.InputViolation;
import com.cryptopilot.trading.model.RiskCalculation;
import com.cryptopilot.trading.model.RiskInput;
import com.cryptopilot.trading.model.RiskInputRejected;
import com.cryptopilot.trading.model.RiskOutcome;
import com.cryptopilot.trading.model.RiskProfileLimits;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The sizing of TECHNICAL_DESIGN 7.5 against numbers worked by hand: the worked example of 7.6, flooring to the
 * step, both directions, the Spot capital check, the pair's minimums at their boundary, every input rule and the
 * three comparisons with the risk profile.
 *
 * <p>Rule: BR-21, BR-22, BR-23, BR-24, BR-25, BR-30; SRS 3.5.1; CR-03; D-53.
 */
class PositionSizeCalculatorTest {

    /** Tick 0.01, step 0.001, minimum notional 5 USDT. */
    private static final PairFilters FILTERS =
            new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5"));

    /** A test profile with the BALANCED numbers of BR-66: 1 %, 5x, 4 %. */
    private static final RiskProfileLimits PROFILE = new RiskProfileLimits(BigDecimal.ONE, 5, new BigDecimal("4"));

    // ------------------------------------------------------------------ sizing (BR-23, BR-24)

    /** TECHNICAL_DESIGN 7.6: budget 1,000 × 1 % = 10; qty 10 / 5 = 2.000; notional 2 × 100 = 200; reward 2 × 10. */
    @Test
    void BR23_theWorkedExample_sizesTwoUnits_andBR24_givesRisk10Reward20Ratio2() {
        RiskCalculation c = sized(input(MarketType.FUTURES, Direction.LONG, "100", "95", "110", "1000", "1", 10));

        assertThat(c.riskBudget()).isEqualByComparingTo("10");
        assertThat(c.quantity()).isEqualByComparingTo("2.000");
        assertThat(c.notional()).isEqualByComparingTo("200");
        assertThat(c.riskAmount()).isEqualByComparingTo("10");
        assertThat(c.rewardAmount()).isEqualByComparingTo("20");
        assertThat(c.riskRewardRatio()).isEqualByComparingTo("2");
        assertThat(c.exceedsCapital())
                .as("Futures compares the margin, not the notional")
                .isFalse();
    }

    /** 10 / 3 = 3.3333… floored to the step: 3.333, so the risk 3.333 × 3 = 9.999 stays within the budget of 10. */
    @Test
    void BR23_theQuantity_isFlooredToTheStep_soTheRiskNeverExceedsTheBudget() {
        RiskCalculation c = sized(input(MarketType.SPOT, Direction.LONG, "100", "97", "106", "1000", "1", 1));

        assertThat(c.quantity()).isEqualByComparingTo("3.333");
        assertThat(c.riskAmount()).isEqualByComparingTo("9.999");
        assertThat(c.rewardAmount()).isEqualByComparingTo("19.998");
        assertThat(c.riskRewardRatio()).isEqualByComparingTo("2");
        assertThat(c.riskAmount()).isLessThanOrEqualTo(c.riskBudget());
    }

    /** SHORT: budget 1,000 × 2 % = 20; qty 20 / |100 − 105| = 4; reward 4 × |90 − 100| = 40; ratio 2. */
    @Test
    void BR23_aShortPlan_isSizedOnTheStopAboveTheEntry() {
        RiskCalculation c = sized(input(MarketType.FUTURES, Direction.SHORT, "100", "105", "90", "1000", "2", 3));

        assertThat(c.quantity()).isEqualByComparingTo("4");
        assertThat(c.notional()).isEqualByComparingTo("400");
        assertThat(c.riskAmount()).isEqualByComparingTo("20");
        assertThat(c.rewardAmount()).isEqualByComparingTo("40");
        assertThat(c.riskRewardRatio()).isEqualByComparingTo("2");
    }

    /** CR-03: the ratio keeps full precision, 1 / 3 is not rounded for display. */
    @Test
    void BR24_theRatio_keepsFullPrecision() {
        RiskCalculation c = sized(input(MarketType.SPOT, Direction.LONG, "100", "97", "101", "1000", "1", 1));

        assertThat(c.riskRewardRatio().precision()).isGreaterThan(20);
        assertThat(c.riskRewardRatio()).isBetween(new BigDecimal("0.3333"), new BigDecimal("0.3334"));
    }

    // ------------------------------------------------------------------ Spot capital (BR-25)

    /** Budget 1,000 × 2 % = 20; qty 20 / 1 = 20; notional 20 × 100 = 2,000 > 1,000 → BLOCKING INSUFFICIENT_CAPITAL. */
    @Test
    void BR25_aSpotNotionalAboveTheCapital_isFlagged() {
        assertThat(sized(input(MarketType.SPOT, Direction.LONG, "100", "99", "103", "1000", "2", 1))
                        .exceedsCapital())
                .isTrue();
    }

    /** Budget 20; qty 20 / 2 = 10; notional 10 × 100 = 1,000 = capital: allowed. */
    @Test
    void BR25_aSpotNotionalEqualToTheCapital_isNotFlagged() {
        RiskCalculation c = sized(input(MarketType.SPOT, Direction.LONG, "100", "98", "104", "1000", "2", 1));

        assertThat(c.notional()).isEqualByComparingTo("1000");
        assertThat(c.exceedsCapital()).isFalse();
    }

    // ------------------------------------------------------------------ pair minimums (MSG15)

    /** Step 1: budget 100 × 1 % = 1; qty 1 / 10 = 0.1 floors to 0 → MSG15 on the quantity. */
    @Test
    void BR23_aQuantityThatFloorsToZero_isRefusedWithMsg15() {
        PairFilters wholeUnits = new PairFilters(new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("5"));
        RiskInput in = new RiskInput(
                MarketType.SPOT,
                Direction.LONG,
                new BigDecimal("100"),
                new BigDecimal("90"),
                new BigDecimal("120"),
                new BigDecimal("100"),
                BigDecimal.ONE,
                1,
                wholeUnits,
                PROFILE,
                BigDecimal.ZERO);

        assertThat(violations(in))
                .extracting(InputViolation::field, InputViolation::messageCode)
                .containsExactly(tuple("quantity", "MSG15"));
    }

    /** Budget 100 × 1 % = 1; qty 1 / 20 = 0.05; notional 0.05 × 100 = 5 = the minimum: accepted. */
    @Test
    void BR23_aNotionalEqualToTheMinimum_isAccepted() {
        assertThat(sized(input(MarketType.SPOT, Direction.LONG, "100", "80", "140", "100", "1", 1))
                        .notional())
                .isEqualByComparingTo("5");
    }

    /** Budget 99.9 × 1 % = 0.999; qty 0.04995 floors to 0.049; notional 4.9 < 5 → MSG15 on the notional. */
    @Test
    void BR23_aNotionalJustBelowTheMinimum_isRefusedWithMsg15() {
        assertThat(violations(input(MarketType.SPOT, Direction.LONG, "100", "80", "140", "99.9", "1", 1)))
                .extracting(InputViolation::field, InputViolation::messageCode)
                .containsExactly(tuple("notional", "MSG15"));
    }

    // ------------------------------------------------------------------ inputs (BR-21, BR-22, BR-30)

    /** Entry = stop loss is a price-order violation, reported before any division by the zero distance. */
    @Test
    void BR22_anEntryEqualToTheStop_isRefusedWithMsg16_withoutDividingByZero() {
        assertThat(violations(input(MarketType.FUTURES, Direction.LONG, "100", "100", "110", "1000", "1", 1)))
                .extracting(InputViolation::field, InputViolation::messageCode)
                .containsExactly(tuple("stopLoss", "MSG16"));
    }

    @Test
    void BR22_pricesOnTheWrongSideOfTheEntry_areRefusedWithMsg16() {
        assertThat(violations(input(MarketType.FUTURES, Direction.LONG, "100", "95", "99", "1000", "1", 1)))
                .extracting(InputViolation::field)
                .containsExactly("takeProfit");
        assertThat(violations(input(MarketType.FUTURES, Direction.SHORT, "100", "95", "90", "1000", "1", 1)))
                .extracting(InputViolation::field)
                .containsExactly("stopLoss");
        assertThat(violations(input(MarketType.FUTURES, Direction.SHORT, "100", "105", "100", "1000", "1", 1)))
                .extracting(InputViolation::field, InputViolation::messageCode)
                .containsExactly(tuple("takeProfit", "MSG16"));
    }

    @Test
    void BR21_aSpotShort_isRefused() {
        assertThat(violations(input(MarketType.SPOT, Direction.SHORT, "100", "105", "90", "1000", "1", 1)))
                .extracting(InputViolation::field, InputViolation::messageCode)
                .containsExactly(tuple("direction", "MSG01"));
    }

    @Test
    void BR21_aSpotPlanWithLeverage_isRefused() {
        assertThat(violations(input(MarketType.SPOT, Direction.LONG, "100", "95", "110", "1000", "1", 3)))
                .extracting(InputViolation::field, InputViolation::messageCode)
                .containsExactly(tuple("leverage", "MSG01"));
    }

    @Test
    void BR30_aLeverageBelowOne_isRefused() {
        assertThat(violations(input(MarketType.FUTURES, Direction.LONG, "100", "95", "110", "1000", "1", 0)))
                .extracting(InputViolation::field, InputViolation::messageCode)
                .containsExactly(tuple("leverage", "MSG15"));
    }

    @ParameterizedTest(name = "risk {0} %")
    @ValueSource(strings = {"0.1", "10"})
    void BR30_theRiskPercentBounds_areAccepted(String percent) {
        assertThat(PositionSizeCalculator.calculate(
                        input(MarketType.FUTURES, Direction.LONG, "100", "95", "110", "1000", percent, 1)))
                .isInstanceOf(RiskCalculation.class);
    }

    @ParameterizedTest(name = "risk {0} %")
    @ValueSource(strings = {"0.09", "10.01", "0", "-1"})
    void BR30_aRiskPercentOutsideTheRange_isRefusedWithMsg15(String percent) {
        assertThat(violations(input(MarketType.FUTURES, Direction.LONG, "100", "95", "110", "1000", percent, 1)))
                .extracting(InputViolation::field, InputViolation::messageCode)
                .containsExactly(tuple("riskPercent", "MSG15"));
    }

    @ParameterizedTest(name = "capital {0}")
    @ValueSource(strings = {"0", "-100"})
    void BR30_aCapitalThatIsNotAboveZero_isRefused(String capital) {
        assertThat(violations(input(MarketType.FUTURES, Direction.LONG, "100", "95", "110", capital, "1", 1)))
                .extracting(InputViolation::field)
                .containsExactly("capital");
    }

    @Test
    void BR30_aPriceOffTheTick_isRefusedWithMsg15() {
        assertThat(violations(input(MarketType.FUTURES, Direction.LONG, "100.005", "95", "110", "1000", "1", 1)))
                .extracting(InputViolation::field, InputViolation::messageCode)
                .containsExactly(tuple("entryPrice", "MSG15"));
    }

    @Test
    void BR30_aPriceThatIsNotAboveZero_isRefused_andThePriceOrderIsNotJudged() {
        assertThat(violations(input(MarketType.FUTURES, Direction.LONG, "100", "0", "110", "1000", "1", 1)))
                .extracting(InputViolation::field)
                .containsExactly("stopLoss");
    }

    /** Every violation is reported at once, so each field can show its own message. */
    @Test
    void BR30_everyViolation_isReportedTogether() {
        assertThat(violations(input(MarketType.SPOT, Direction.SHORT, "100", "105", "90", "0", "20", 2)))
                .extracting(InputViolation::field)
                .containsExactlyInAnyOrder("direction", "leverage", "capital", "riskPercent");
    }

    // ------------------------------------------------------------------ the risk profile (D-53, TECHNICAL_DESIGN 7.5)

    /** 1 % against a 1 % profile is not above it; 1.5 % is. */
    @Test
    void BR66_theRiskPercent_isComparedWithTheProfilesRiskPerTrade() {
        assertThat(sized(input(MarketType.FUTURES, Direction.LONG, "100", "95", "110", "1000", "1", 1))
                        .riskPercentAboveProfile())
                .isFalse();
        assertThat(sized(input(MarketType.FUTURES, Direction.LONG, "100", "95", "110", "1000", "1.5", 1))
                        .riskPercentAboveProfile())
                .isTrue();
    }

    /** 5x against a 5x profile is not above it; 6x is. */
    @Test
    void BR66_theLeverage_isComparedWithTheProfilesMaximum() {
        assertThat(sized(input(MarketType.FUTURES, Direction.LONG, "100", "95", "110", "1000", "1", 5))
                        .leverageAboveProfile())
                .isFalse();
        assertThat(sized(input(MarketType.FUTURES, Direction.LONG, "100", "95", "110", "1000", "1", 6))
                        .leverageAboveProfile())
                .isTrue();
    }

    /** Risk 10 + other 30 = 40 of 1,000 = 4 % = the maximum; with 31 it is 4.1 %, above it. */
    @Test
    void BR66_theTotalOpenRisk_isComparedWithTheProfilesMaximum() {
        RiskCalculation atMax = sized(withOtherOpenRisk("30"));
        RiskCalculation above = sized(withOtherOpenRisk("31"));

        assertThat(atMax.totalOpenRiskPercent()).isEqualByComparingTo("4");
        assertThat(atMax.totalOpenRiskAboveProfile()).isFalse();
        assertThat(above.totalOpenRiskPercent()).isEqualByComparingTo("4.1");
        assertThat(above.totalOpenRiskAboveProfile()).isTrue();
    }

    // ------------------------------------------------------------------ programming errors

    @Test
    void missingOrImpossibleValues_areProgrammingErrors() {
        assertThatNullPointerException()
                .isThrownBy(() -> new RiskInput(
                        null,
                        Direction.LONG,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        1,
                        FILTERS,
                        PROFILE,
                        BigDecimal.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> withOtherOpenRisk("-1"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RiskProfileLimits(BigDecimal.ZERO, 5, BigDecimal.ONE));
        assertThatIllegalArgumentException().isThrownBy(() -> new RiskProfileLimits(BigDecimal.ONE, 0, BigDecimal.ONE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RiskProfileLimits(BigDecimal.ONE, 5, new BigDecimal("-1")));
        assertThatIllegalArgumentException().isThrownBy(() -> new RiskInputRejected(List.of()));
        assertThatNullPointerException().isThrownBy(() -> new InputViolation("f", null, "d"));
    }

    // ------------------------------------------------------------------ fixtures

    private static RiskInput input(
            MarketType market,
            Direction direction,
            String entry,
            String stop,
            String takeProfit,
            String capital,
            String riskPercent,
            int leverage) {
        return new RiskInput(
                market,
                direction,
                new BigDecimal(entry),
                new BigDecimal(stop),
                new BigDecimal(takeProfit),
                new BigDecimal(capital),
                new BigDecimal(riskPercent),
                leverage,
                FILTERS,
                PROFILE,
                BigDecimal.ZERO);
    }

    private static RiskInput withOtherOpenRisk(String other) {
        RiskInput base = input(MarketType.FUTURES, Direction.LONG, "100", "95", "110", "1000", "1", 1);
        return new RiskInput(
                base.market(),
                base.direction(),
                base.entryPrice(),
                base.stopLoss(),
                base.takeProfit(),
                base.capital(),
                base.riskPercent(),
                base.leverage(),
                base.filters(),
                base.profile(),
                new BigDecimal(other));
    }

    private static RiskCalculation sized(RiskInput in) {
        RiskOutcome outcome = PositionSizeCalculator.calculate(in);
        assertThat(outcome).isInstanceOf(RiskCalculation.class);
        return (RiskCalculation) outcome;
    }

    private static List<InputViolation> violations(RiskInput in) {
        RiskOutcome outcome = PositionSizeCalculator.calculate(in);
        assertThat(outcome).isInstanceOf(RiskInputRejected.class);
        return ((RiskInputRejected) outcome).violations();
    }
}
