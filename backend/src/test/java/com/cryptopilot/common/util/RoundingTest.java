package com.cryptopilot.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Boundary tests for the rounding policy of TECHNICAL_DESIGN section 5.4. Every case that sits
 * exactly on a rounding boundary is spelled out, because those are the values where a wrong
 * {@link RoundingMode} is invisible in ordinary use and wrong in the number that matters.
 */
class RoundingTest {

    @Nested
    @DisplayName("quantity rounded down to the step size")
    class ToStepSize {

        @Test
        void quantityBetweenTwoSteps_roundsDown() {
            BigDecimal quantity = Rounding.toStepSize(new BigDecimal("0.0247"), new BigDecimal("0.001"));

            assertThat(quantity).isEqualByComparingTo("0.024");
            assertThat(quantity.scale()).isEqualTo(3);
        }

        @Test
        void quantityJustBelowTheNextStep_staysOnTheLowerStep() {
            assertThat(Rounding.toStepSize(new BigDecimal("0.0249999"), new BigDecimal("0.001")))
                    .isEqualByComparingTo("0.024");
        }

        @Test
        void quantityExactlyOnAStep_isUnchanged() {
            assertThat(Rounding.toStepSize(new BigDecimal("0.024"), new BigDecimal("0.001")))
                    .isEqualByComparingTo("0.024");
        }

        @Test
        void quantityHalfWayBetweenSteps_roundsDownAndNeverUp() {
            assertThat(Rounding.toStepSize(new BigDecimal("0.0245"), new BigDecimal("0.001")))
                    .isEqualByComparingTo("0.024");
        }

        @Test
        void quantityBelowOneStep_becomesZero() {
            assertThat(Rounding.toStepSize(new BigDecimal("0.0009"), new BigDecimal("0.001")))
                    .isEqualByComparingTo("0");
        }

        @Test
        void wholeNumberStep_roundsDownToTheStep() {
            assertThat(Rounding.toStepSize(new BigDecimal("17.9"), new BigDecimal("10")))
                    .isEqualByComparingTo("10");
        }

        @Test
        void zeroQuantity_isZero() {
            assertThat(Rounding.toStepSize(BigDecimal.ZERO, new BigDecimal("0.001")))
                    .isEqualByComparingTo("0");
        }

        @Test
        void negativeQuantity_isRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> Rounding.toStepSize(new BigDecimal("-0.1"), BigDecimal.ONE))
                    .withMessageContaining("quantity must not be negative");
        }

        @Test
        void zeroStepSize_isRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> Rounding.toStepSize(BigDecimal.ONE, BigDecimal.ZERO))
                    .withMessageContaining("stepSize must be positive");
        }

        @Test
        void nullArguments_areRejected() {
            assertThatNullPointerException().isThrownBy(() -> Rounding.toStepSize(null, BigDecimal.ONE));
            assertThatNullPointerException().isThrownBy(() -> Rounding.toStepSize(BigDecimal.ONE, null));
        }
    }

    @Nested
    @DisplayName("price rounded to the tick size in the direction the caller names")
    class ToTickSize {

        @ParameterizedTest(name = "{0} rounded {1} to a 0.01 tick is {2}")
        @CsvSource({
            "100.005, HALF_UP, 100.01",
            "100.004, HALF_UP, 100.00",
            "100.015, HALF_UP, 100.02",
            "100.001, CEILING, 100.01",
            "100.010, CEILING, 100.01",
            "100.009, FLOOR, 100.00",
            "100.010, FLOOR, 100.01",
            "100.009, DOWN, 100.00",
            "100.001, UP, 100.01"
        })
        void priceOnARoundingBoundary_followsTheRequestedMode(String price, RoundingMode mode, String expected) {
            assertThat(Rounding.toTickSize(new BigDecimal(price), new BigDecimal("0.01"), mode))
                    .isEqualByComparingTo(expected);
        }

        @Test
        void halfWayValue_roundsUpUnderHalfUpAndDownUnderHalfDown() {
            BigDecimal halfWay = new BigDecimal("27123.455");
            BigDecimal tick = new BigDecimal("0.01");

            assertThat(Rounding.toTickSize(halfWay, tick, RoundingMode.HALF_UP)).isEqualByComparingTo("27123.46");
            assertThat(Rounding.toTickSize(halfWay, tick, RoundingMode.HALF_DOWN))
                    .isEqualByComparingTo("27123.45");
        }

        @Test
        void resultCarriesTheScaleOfTheTick() {
            assertThat(Rounding.toTickSize(new BigDecimal("27123.4567"), new BigDecimal("0.10"), RoundingMode.HALF_UP)
                            .scale())
                    .isEqualTo(1);
        }

        @Test
        void priceAlreadyOnATick_isUnchangedEvenWithUnnecessary() {
            assertThat(Rounding.toTickSize(new BigDecimal("100.01"), new BigDecimal("0.01"), RoundingMode.UNNECESSARY))
                    .isEqualByComparingTo("100.01");
        }

        @Test
        void priceOffTheTick_withUnnecessary_throws() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> Rounding.toTickSize(
                            new BigDecimal("100.015"), new BigDecimal("0.01"), RoundingMode.UNNECESSARY));
        }

        @Test
        void negativePriceAndNonPositiveTick_areRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> Rounding.toTickSize(new BigDecimal("-1"), BigDecimal.ONE, RoundingMode.HALF_UP))
                    .withMessageContaining("price must not be negative");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(
                            () -> Rounding.toTickSize(BigDecimal.ONE, new BigDecimal("-0.01"), RoundingMode.HALF_UP))
                    .withMessageContaining("tickSize must be positive");
        }

        @Test
        void nullArguments_areRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Rounding.toTickSize(null, BigDecimal.ONE, RoundingMode.HALF_UP));
            assertThatNullPointerException()
                    .isThrownBy(() -> Rounding.toTickSize(BigDecimal.ONE, null, RoundingMode.HALF_UP));
            assertThatNullPointerException()
                    .isThrownBy(() -> Rounding.toTickSize(BigDecimal.ONE, BigDecimal.ONE, null));
        }
    }

    @Nested
    @DisplayName("stored amount at scale 8, half to even")
    class ToAmount {

        @Test
        void halfWayValue_roundsToTheEvenNeighbour() {
            assertThat(Rounding.toAmount(new BigDecimal("0.000000005"))).isEqualByComparingTo("0.00000000");
            assertThat(Rounding.toAmount(new BigDecimal("0.000000015"))).isEqualByComparingTo("0.00000002");
        }

        @Test
        void valueJustEitherSideOfHalf_roundsTheOrdinaryWay() {
            assertThat(Rounding.toAmount(new BigDecimal("0.0000000049"))).isEqualByComparingTo("0.00000000");
            assertThat(Rounding.toAmount(new BigDecimal("0.0000000051"))).isEqualByComparingTo("0.00000001");
        }

        @Test
        void negativeHalfWayValue_roundsToTheEvenNeighbourToo() {
            assertThat(Rounding.toAmount(new BigDecimal("-0.000000025"))).isEqualByComparingTo("-0.00000002");
        }

        @Test
        void shorterValue_isPaddedToTheStoredScale() {
            assertThat(Rounding.toAmount(new BigDecimal("12.5")).scale()).isEqualTo(Rounding.AMOUNT_SCALE);
        }

        @Test
        void nullAmount_isRejected() {
            assertThatNullPointerException().isThrownBy(() -> Rounding.toAmount(null));
        }
    }

    @Nested
    @DisplayName("intermediate division")
    class Divide {

        @Test
        void nonTerminatingQuotient_isKeptAtFullWorkingPrecision() {
            BigDecimal third = Rounding.divide(BigDecimal.ONE, new BigDecimal("3"));

            assertThat(third.precision()).isEqualTo(Rounding.DIVISION.getPrecision());
            assertThat(third.toPlainString()).startsWith("0.3333333333");
        }

        @Test
        void exactQuotient_isExact() {
            assertThat(Rounding.divide(new BigDecimal("27123.45"), new BigDecimal("2")))
                    .isEqualByComparingTo("13561.725");
        }

        @Test
        void divisionByZero_throws() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> Rounding.divide(BigDecimal.ONE, BigDecimal.ZERO));
        }

        @Test
        void nullArguments_areRejected() {
            assertThatNullPointerException().isThrownBy(() -> Rounding.divide(null, BigDecimal.ONE));
            assertThatNullPointerException().isThrownBy(() -> Rounding.divide(BigDecimal.ONE, null));
        }
    }
}
