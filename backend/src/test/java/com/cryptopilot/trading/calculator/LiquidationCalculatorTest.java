package com.cryptopilot.trading.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.trading.Direction;
import com.cryptopilot.trading.model.InputViolation;
import com.cryptopilot.trading.model.LeverageBracket;
import com.cryptopilot.trading.model.LiquidationEstimate;
import com.cryptopilot.trading.model.LiquidationInput;
import com.cryptopilot.trading.model.LiquidationOutcome;
import com.cryptopilot.trading.model.RiskCalculation;
import com.cryptopilot.trading.model.RiskInput;
import com.cryptopilot.trading.model.RiskInputRejected;
import com.cryptopilot.trading.model.RiskProfileLimits;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The margin and liquidation price of TECHNICAL_DESIGN 7.6 against numbers worked by hand: the worked example, a
 * bracket with a maintenance amount, the bracket boundary, the bracket's maximum leverage and both sides of BR-28.
 *
 * <p>Rule: BR-26, BR-27, BR-28; TECHNICAL_DESIGN 5.4 and 7.6; A-01.
 */
class LiquidationCalculatorTest {

    private static final BigDecimal TICK = new BigDecimal("0.01");

    /** Brackets shaped like an exchange's: each amount is the one that keeps the maintenance margin continuous. */
    private static final LeverageBracket B1 = bracket(1, "0", "50000", 125, "0.004", "0");

    private static final LeverageBracket B2 = bracket(2, "50000", "250000", 100, "0.005", "50");
    private static final LeverageBracket B3 = bracket(3, "250000", "1000000", 50, "0.01", "1300");
    private static final List<LeverageBracket> BRACKETS = List.of(B1, B2, B3);

    // ------------------------------------------------------------------ worked example (TECHNICAL_DESIGN 7.6)

    /** E 100, qty 2, L 10: margin 20; LP = 100 × 0.9 / 0.996 = 90.3614… → ceiling 90.37; stop 95 above it. */
    @Test
    void BR27_theWorkedExample_long10x_givesLiquidation9037() {
        LiquidationEstimate e = estimate(Direction.LONG, "100", "95", "2", 10, "1000");

        assertThat(e.bracket().bracketNo()).isEqualTo(1);
        assertThat(e.notional()).isEqualByComparingTo("200");
        assertThat(e.initialMargin()).isEqualByComparingTo("20");
        assertThat(e.maintenanceMargin()).isEqualByComparingTo("0.8");
        assertThat(e.liquidationPrice()).isEqualByComparingTo("90.37");
        assertThat(e.stopBeyondLiquidation()).isFalse();
    }

    /** SHORT E 100, qty 2, L 10: LP = 100 × 1.1 / 1.004 = 109.5617… → floor 109.56. */
    @Test
    void BR27_theWorkedExample_short10x_givesLiquidation10956() {
        LiquidationEstimate e = estimate(Direction.SHORT, "100", "105", "2", 10, "1000");

        assertThat(e.liquidationPrice()).isEqualByComparingTo("109.56");
        assertThat(e.stopBeyondLiquidation()).isFalse();
    }

    /** In bracket 1 the maintenance amount is 0, so the result is the simplified formula E(1 ∓ 1/L) / (1 ∓ MMR). */
    @ParameterizedTest(name = "{0}x")
    @ValueSource(ints = {1, 2, 5, 10, 20, 50, 125})
    void BR27_inBracketOne_theResultIsTheFormulaWithoutMaintenanceAmount(int leverage) {
        BigDecimal entry = new BigDecimal("100");
        BigDecimal mmr = B1.maintenanceMarginRate();
        BigDecimal step = BigDecimal.ONE.divide(BigDecimal.valueOf(leverage), MathContext.DECIMAL128);
        BigDecimal longRaw = entry.multiply(BigDecimal.ONE.subtract(step))
                .divide(BigDecimal.ONE.subtract(mmr), MathContext.DECIMAL128);
        BigDecimal shortRaw =
                entry.multiply(BigDecimal.ONE.add(step)).divide(BigDecimal.ONE.add(mmr), MathContext.DECIMAL128);

        LiquidationEstimate longs = estimate(Direction.LONG, "100", "1", "2", leverage, "1000");
        LiquidationEstimate shorts = estimate(Direction.SHORT, "100", "300", "2", leverage, "1000");

        assertThat(longs.liquidationPrice())
                .isEqualByComparingTo(
                        longRaw.divide(TICK, 0, RoundingMode.CEILING).multiply(TICK));
        assertThat(shorts.liquidationPrice())
                .isEqualByComparingTo(
                        shortRaw.divide(TICK, 0, RoundingMode.FLOOR).multiply(TICK));
    }

    // ------------------------------------------------------------------ maintenance amount (A-01)

    /**
     * Bracket 2 by hand: E 100, qty 600 → N 60,000; L 10 → WB 6,000; MMR 0.005, cum 50; MM = 300 − 50 = 250.
     * LONG LP = (60,000 − 6,000 − 50) / (600 × 0.995) = 53,950 / 597 = 90.3685… → ceiling 90.37 (without cum
     * 54,000 / 597 = 90.4522… → 90.46). SHORT LP = (60,000 + 6,000 + 50) / (600 × 1.005) = 66,050 / 603 =
     * 109.5356… → floor 109.53 (without cum 66,000 / 603 → 109.45).
     */
    @Test
    void BR27_aBracketWithMaintenanceAmount_matchesTheHandCalculation() {
        LiquidationEstimate longs = estimate(Direction.LONG, "100", "95", "600", 10, "10000");
        LiquidationEstimate shorts = estimate(Direction.SHORT, "100", "105", "600", 10, "10000");

        assertThat(longs.bracket().bracketNo()).isEqualTo(2);
        assertThat(longs.notional()).isEqualByComparingTo("60000");
        assertThat(longs.initialMargin()).isEqualByComparingTo("6000");
        assertThat(longs.maintenanceMargin()).isEqualByComparingTo("250");
        assertThat(longs.liquidationPrice()).isEqualByComparingTo("90.37");
        assertThat(shorts.liquidationPrice()).isEqualByComparingTo("109.53");
    }

    /** cum_1 = 0; cum_2 = 0 + 50,000 × 0.001 = 50; cum_3 = 50 + 250,000 × 0.005 = 1,300; given amounts kept. */
    @Test
    void BR27_missingMaintenanceAmounts_areDerivedFromTheBracketsBelow() {
        List<LeverageBracket> resolved = LiquidationCalculator.withMaintenanceAmounts(List.of(
                B3.withMaintenanceAmount(null), B1.withMaintenanceAmount(null), B2.withMaintenanceAmount(null)));

        assertThat(resolved)
                .extracting(
                        LeverageBracket::bracketNo, b -> b.maintenanceAmount().stripTrailingZeros())
                .containsExactly(
                        tuple(1, BigDecimal.ZERO),
                        tuple(2, new BigDecimal("5E+1")),
                        tuple(3, new BigDecimal("1.3E+3")));
        assertThat(LiquidationCalculator.withMaintenanceAmounts(List.of(B1, B2.withMaintenanceAmount(BigDecimal.TEN)))
                        .get(1)
                        .maintenanceAmount())
                .isEqualByComparingTo("10");
    }

    // ------------------------------------------------------------------ bracket boundary

    /**
     * E 1, tick 0.0001: qty 50,000 is exactly bracket 2's floor, qty 49,999.999 is ε = 0.001 below it in bracket 1.
     * MM: 50,000 × 0.005 − 50 = 200 and 49,999.999 × 0.004 − 0 = 199.999996, equal within ε × MMR.
     */
    @Test
    void BR27_atABracketFloor_theMaintenanceMarginIsContinuous() {
        LiquidationEstimate atFloor = estimate(Direction.LONG, "1", "0.5", "50000", 10, "100000", "0.0001", BRACKETS);
        LiquidationEstimate below = estimate(Direction.LONG, "1", "0.5", "49999.999", 10, "100000", "0.0001", BRACKETS);

        assertThat(atFloor.bracket().bracketNo()).isEqualTo(2);
        assertThat(below.bracket().bracketNo()).isEqualTo(1);
        assertThat(atFloor.maintenanceMargin()).isEqualByComparingTo("200");
        assertThat(below.maintenanceMargin()).isEqualByComparingTo("199.999996");
        assertThat(atFloor.maintenanceMargin()
                        .subtract(below.maintenanceMargin())
                        .abs())
                .isLessThanOrEqualTo(new BigDecimal("0.00001"));
    }

    /** Without the maintenance amount the maintenance margin jumps by N × ΔMMR = 50 at the same floor. */
    @Test
    void BR27_withoutMaintenanceAmount_theFloorWouldJump() {
        List<LeverageBracket> noCum = List.of(B1, B2.withMaintenanceAmount(BigDecimal.ZERO));

        LiquidationEstimate atFloor = estimate(Direction.LONG, "1", "0.5", "50000", 10, "100000", "0.0001", noCum);

        assertThat(atFloor.maintenanceMargin()).isEqualByComparingTo("250");
    }

    // ------------------------------------------------------------------ maximum leverage (BR-26)

    /** N 60,000 is in bracket 2, whose maximum is 100x: 100 is accepted, 101 refused with MSG15 on the leverage. */
    @Test
    void BR26_theBracketsMaximumLeverage_isAccepted_andOneMoreIsRefused() {
        assertThat(calculate(Direction.LONG, "100", "99.5", "600", 100, "10000", TICK.toPlainString(), BRACKETS))
                .isInstanceOf(LiquidationEstimate.class);

        LiquidationOutcome refused =
                calculate(Direction.LONG, "100", "99.5", "600", 101, "10000", TICK.toPlainString(), BRACKETS);

        assertThat(refused).isInstanceOf(RiskInputRejected.class);
        assertThat(((RiskInputRejected) refused).violations())
                .extracting(InputViolation::field, InputViolation::messageCode)
                .containsExactly(tuple("leverage", "MSG15"));
    }

    /** The maximum is the bracket's, not the pair's: 125x is allowed in bracket 1 but not in bracket 2. */
    @Test
    void BR26_theMaximumFollowsTheBracketOfTheNotional() {
        assertThat(calculate(Direction.LONG, "100", "99.5", "2", 125, "10000", "0.01", BRACKETS))
                .isInstanceOf(LiquidationEstimate.class);
        assertThat(calculate(Direction.LONG, "100", "99.5", "600", 125, "10000", "0.01", BRACKETS))
                .isInstanceOf(RiskInputRejected.class);
    }

    @Test
    void BR26_aNotionalAboveTheLargestBracket_isRefused() {
        LiquidationOutcome refused = calculate(Direction.LONG, "100", "95", "10000", 1, "10000000", "0.01", BRACKETS);

        assertThat(((RiskInputRejected) refused).violations())
                .extracting(InputViolation::field, InputViolation::messageCode)
                .containsExactly(tuple("notional", "MSG15"));
    }

    /** Margin 200 / 10 = 20: a capital of 20 covers it, 19.99 does not (BLOCKING INSUFFICIENT_CAPITAL). */
    @Test
    void BR26_aMarginAboveTheCapital_isFlagged_andEqualIsNot() {
        assertThat(estimate(Direction.LONG, "100", "95", "2", 10, "20").marginExceedsCapital())
                .isFalse();
        assertThat(estimate(Direction.LONG, "100", "95", "2", 10, "19.99").marginExceedsCapital())
                .isTrue();
    }

    // ------------------------------------------------------------------ stop loss against liquidation (BR-28)

    /** LONG LP 90.37 at 10x: stop 90.38 is above it; 90.37 is not. */
    @Test
    void BR28_long_aStopAboveTheLiquidationPrice_passes_andOneAtItIsBlocked() {
        assertThat(estimate(Direction.LONG, "100", "90.38", "2", 10, "1000").stopBeyondLiquidation())
                .isFalse();
        assertThat(estimate(Direction.LONG, "100", "90.37", "2", 10, "1000").stopBeyondLiquidation())
                .isTrue();
    }

    /** TECHNICAL_DESIGN 7.6 at 20x: LP = 95 / 0.996 = 95.3815… → 95.39, so the stop at 95 is beyond it. */
    @Test
    void BR28_long_theWorkedExampleAt20x_isBlocked() {
        LiquidationEstimate e = estimate(Direction.LONG, "100", "95", "2", 20, "1000");

        assertThat(e.liquidationPrice()).isEqualByComparingTo("95.39");
        assertThat(e.stopBeyondLiquidation()).isTrue();
    }

    /** SHORT LP 109.56 at 10x: stop 109.55 is below it; 109.56 is not. */
    @Test
    void BR28_short_aStopBelowTheLiquidationPrice_passes_andOneAtItIsBlocked() {
        assertThat(estimate(Direction.SHORT, "100", "109.55", "2", 10, "1000").stopBeyondLiquidation())
                .isFalse();
        assertThat(estimate(Direction.SHORT, "100", "109.56", "2", 10, "1000").stopBeyondLiquidation())
                .isTrue();
    }

    /** SHORT at 20x: LP = 105 / 1.004 = 104.5816… → 104.58, so the stop at 105 is beyond it. */
    @Test
    void BR28_short_aStopAbove20xLiquidation_isBlocked() {
        LiquidationEstimate e = estimate(Direction.SHORT, "100", "105", "2", 20, "1000");

        assertThat(e.liquidationPrice()).isEqualByComparingTo("104.58");
        assertThat(e.stopBeyondLiquidation()).isTrue();
    }

    // ------------------------------------------------------------------ edges and wiring

    /** 1x LONG with a maintenance amount: (N − N − cum) is negative, so no price liquidates it; reported as 0. */
    @Test
    void BR27_aLong1xWithMaintenanceAmount_cannotBeLiquidated() {
        LiquidationEstimate e = estimate(Direction.LONG, "100", "95", "600", 1, "100000");

        assertThat(e.liquidationPrice()).isEqualByComparingTo("0");
        assertThat(e.stopBeyondLiquidation()).isFalse();
    }

    /** The quantity and notional come from T-035's sizing of the same worked example. */
    @Test
    void BR27_theInput_takesTheQuantityOfTheRiskCalculation() {
        RiskInput plan = new RiskInput(
                MarketType.FUTURES,
                Direction.LONG,
                new BigDecimal("100"),
                new BigDecimal("95"),
                new BigDecimal("110"),
                new BigDecimal("1000"),
                BigDecimal.ONE,
                10,
                new PairFilters(TICK, new BigDecimal("0.001"), new BigDecimal("5")),
                new RiskProfileLimits(BigDecimal.ONE, 10, new BigDecimal("4")),
                BigDecimal.ZERO);
        RiskCalculation sized = (RiskCalculation) PositionSizeCalculator.calculate(plan);

        LiquidationEstimate e =
                (LiquidationEstimate) LiquidationCalculator.calculate(LiquidationInput.of(plan, sized, BRACKETS));

        assertThat(e.notional()).isEqualByComparingTo(sized.notional());
        assertThat(e.liquidationPrice()).isEqualByComparingTo("90.37");
    }

    @Test
    void BR21_aSpotPlan_hasNoLiquidationInput() {
        RiskInput spot = new RiskInput(
                MarketType.SPOT,
                Direction.LONG,
                new BigDecimal("100"),
                new BigDecimal("95"),
                new BigDecimal("110"),
                new BigDecimal("1000"),
                BigDecimal.ONE,
                1,
                new PairFilters(TICK, new BigDecimal("0.001"), new BigDecimal("5")),
                new RiskProfileLimits(BigDecimal.ONE, 10, new BigDecimal("4")),
                BigDecimal.ZERO);
        RiskCalculation sized = (RiskCalculation) PositionSizeCalculator.calculate(spot);

        assertThatIllegalArgumentException().isThrownBy(() -> LiquidationInput.of(spot, sized, BRACKETS));
    }

    @Test
    void BR27_aBracketOutsideItsRanges_isAProgrammingError() {
        assertThatIllegalArgumentException().isThrownBy(() -> bracket(1, "100", "100", 10, "0.01", "0"));
        assertThatIllegalArgumentException().isThrownBy(() -> bracket(1, "0", "100", 0, "0.01", "0"));
        assertThatIllegalArgumentException().isThrownBy(() -> bracket(1, "0", "100", 10, "1", "0"));
        assertThatIllegalArgumentException().isThrownBy(() -> bracket(1, "0", "100", 10, "0.01", "-1"));
    }

    // ------------------------------------------------------------------ fixtures

    private static LeverageBracket bracket(int no, String floor, String cap, int max, String mmr, String cum) {
        return new LeverageBracket(
                no,
                new BigDecimal(floor),
                new BigDecimal(cap),
                max,
                new BigDecimal(mmr),
                cum == null ? null : new BigDecimal(cum));
    }

    private static LiquidationEstimate estimate(
            Direction direction, String entry, String stop, String quantity, int leverage, String capital) {
        return estimate(direction, entry, stop, quantity, leverage, capital, TICK.toPlainString(), BRACKETS);
    }

    private static LiquidationEstimate estimate(
            Direction direction,
            String entry,
            String stop,
            String quantity,
            int leverage,
            String capital,
            String tick,
            List<LeverageBracket> brackets) {
        LiquidationOutcome outcome = calculate(direction, entry, stop, quantity, leverage, capital, tick, brackets);
        assertThat(outcome).isInstanceOf(LiquidationEstimate.class);
        return (LiquidationEstimate) outcome;
    }

    private static LiquidationOutcome calculate(
            Direction direction,
            String entry,
            String stop,
            String quantity,
            int leverage,
            String capital,
            String tick,
            List<LeverageBracket> brackets) {
        return LiquidationCalculator.calculate(new LiquidationInput(
                direction,
                new BigDecimal(entry),
                new BigDecimal(stop),
                new BigDecimal(quantity),
                leverage,
                new BigDecimal(capital),
                new BigDecimal(tick),
                brackets));
    }
}
