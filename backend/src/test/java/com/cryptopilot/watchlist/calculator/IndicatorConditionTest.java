package com.cryptopilot.watchlist.calculator;

import static com.cryptopilot.watchlist.model.enums.ConditionOperator.CROSS_ABOVE;
import static com.cryptopilot.watchlist.model.enums.ConditionOperator.CROSS_BELOW;
import static com.cryptopilot.watchlist.model.enums.ConditionOperator.GREATER_THAN;
import static com.cryptopilot.watchlist.model.enums.ConditionOperator.LESS_THAN;
import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.watchlist.model.IndicatorReading;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The condition of an indicator alert on a closed candle: crosses are edge-triggered with the PRICE semantics, the
 * level comparisons are strict, and a line cross compares with zero. Boundary values on both sides of the level.
 *
 * <p>Rule: BR-20; D-76, D-88.
 *
 * <p>Reference: Myers, G. J., Sandler, C. &amp; Badgett, T. (2011). <i>The Art of Software Testing</i> (3rd ed.).
 * Wiley, ch. 4 (boundary value analysis).
 */
class IndicatorConditionTest {

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource({
        "29.9, 30, true", // reaches the level exactly
        "29.9, 30.1, true",
        "29.9, 29.99, false", // stays below
        "30, 31, false", // was not below
        "31, 32, false", // stays above
        "31, 29, false" // goes the other way
    })
    void BR20_crossAbove_firesOnlyFromBelowToTheLevelOrAbove(String previous, String current, boolean met) {
        assertThat(IndicatorCondition.met(CROSS_ABOVE, new BigDecimal("30"), reading(previous, current)))
                .isEqualTo(met);
    }

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource({
        "70.1, 70, true",
        "70.1, 69.9, true",
        "70.1, 70.01, false",
        "70, 69, false",
        "69, 68, false",
        "69, 71, false"
    })
    void BR20_crossBelow_firesOnlyFromAboveToTheLevelOrBelow(String previous, String current, boolean met) {
        assertThat(IndicatorCondition.met(CROSS_BELOW, new BigDecimal("70"), reading(previous, current)))
                .isEqualTo(met);
    }

    @Test
    void BR20_aCross_withoutAPreviousValue_doesNotFire() {
        assertThat(IndicatorCondition.met(CROSS_ABOVE, new BigDecimal("30"), reading(null, "40")))
                .isFalse();
        assertThat(IndicatorCondition.met(CROSS_BELOW, new BigDecimal("30"), reading(null, "20")))
                .isFalse();
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource({
        "GREATER_THAN, 70.0001, true",
        "GREATER_THAN, 70, false",
        "GREATER_THAN, 69.9999, false",
        "LESS_THAN, 29.9999, true",
        "LESS_THAN, 30, false",
        "LESS_THAN, 30.0001, false"
    })
    void D88_levelComparisons_areStrict(ConditionOperator condition, String current, boolean met) {
        BigDecimal level = condition == GREATER_THAN ? new BigDecimal("70") : new BigDecimal("30");

        assertThat(IndicatorCondition.met(condition, level, reading(null, current)))
                .isEqualTo(met);
    }

    @Test
    void D88_aLevelComparison_holdsOnEveryCandleItIsTrue_notOnlyOnTheChange() {
        assertThat(IndicatorCondition.met(GREATER_THAN, new BigDecimal("70"), reading("75", "76")))
                .isTrue();
        assertThat(IndicatorCondition.met(LESS_THAN, new BigDecimal("30"), reading("25", "24")))
                .isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"-0.5, 0, CROSS_ABOVE", "-0.5, 0.2, CROSS_ABOVE", "0.5, 0, CROSS_BELOW", "0.5, -0.2, CROSS_BELOW"})
    void D76_aLineCross_withoutAThreshold_crossesZero(String previous, String current, ConditionOperator condition) {
        assertThat(IndicatorCondition.met(condition, null, reading(previous, current)))
                .isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"0, 0.5, CROSS_ABOVE", "0.1, 0.5, CROSS_ABOVE", "0, -0.5, CROSS_BELOW", "-0.1, -0.5, CROSS_BELOW"})
    void D76_aLineThatStaysOnOneSideOrStartsAtZero_doesNotCross(
            String previous, String current, ConditionOperator condition) {
        assertThat(IndicatorCondition.met(condition, null, reading(previous, current)))
                .isFalse();
    }

    private static IndicatorReading reading(String previous, String current) {
        return new IndicatorReading(previous == null ? null : new BigDecimal(previous), new BigDecimal(current));
    }
}
