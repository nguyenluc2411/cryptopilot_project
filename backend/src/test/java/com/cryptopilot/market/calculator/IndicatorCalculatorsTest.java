package com.cryptopilot.market.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/**
 * Each indicator calculator on a series short enough to compute by hand, so that its seed, its smoothing and its
 * warm-up can be read straight off the test.
 *
 * <p>Rule: BR-12; TECHNICAL_DESIGN 7.2.
 */
class IndicatorCalculatorsTest {

    @Test
    void BR12_sma_isTheMeanOfTheLastValuesOnceItHasThem() {
        SimpleMovingAverage sma = new SimpleMovingAverage(3);

        assertThat(sma.update(1)).isEmpty();
        assertThat(sma.update(2)).isEmpty();
        assertThat(sma.update(3)).hasValue(2);
        assertThat(sma.update(4)).hasValue(3);
        assertThat(sma.update(8)).hasValue(5);
    }

    /** Seeded with the mean of the first three values, then α = 2/(3 + 1) = 0.5. */
    @Test
    void BR12_ema_isSeededWithTheMeanThenSmoothed() {
        ExponentialMovingAverage ema = new ExponentialMovingAverage(3);

        assertThat(ema.update(1)).isEmpty();
        assertThat(ema.update(2)).isEmpty();
        assertThat(ema.update(3)).hasValue(2);
        assertThat(ema.update(4)).hasValue(3);
        assertThat(ema.update(6)).hasValue(4.5);
    }

    /**
     * Period 2 over 10, 11, 10.5, 11.5, 11: the changes +1, −0.5 seed AG = 0.5, AL = 0.25 (RS 2); then +1 gives AG =
     * 0.75, AL = 0.125 (RS 6); then −0.5 gives AG = 0.375, AL = 0.3125 (RS 1.2).
     */
    @Test
    void BR12_rsi_isSeededWithTheMeanChangesThenWilderSmoothed() {
        WilderRsi rsi = new WilderRsi(2);

        assertThat(rsi.update(10)).isEmpty();
        assertThat(rsi.update(11)).isEmpty();
        assertThat(rsi.update(10.5).getAsDouble()).isCloseTo(100 - 100 / 3.0, within(1e-12));
        assertThat(rsi.update(11.5).getAsDouble()).isCloseTo(100 - 100 / 7.0, within(1e-12));
        assertThat(rsi.update(11).getAsDouble()).isCloseTo(100 - 100 / 2.2, within(1e-12));
    }

    @Test
    void BR12_rsi_isHundredWithoutLossesAndZeroWithoutGains() {
        WilderRsi rising = new WilderRsi(3);
        WilderRsi falling = new WilderRsi(3);
        OptionalDouble up = OptionalDouble.empty();
        OptionalDouble down = OptionalDouble.empty();
        for (int i = 0; i < 6; i++) {
            up = rising.update(100 + i);
            down = falling.update(100 - i);
        }

        assertThat(up).hasValue(100);
        assertThat(down).hasValue(0);
    }

    /** Period 2, width 2: over 1 and 3 the mean is 2 and the population deviation 1; over 3 and 5, 4 and 1. */
    @Test
    void BR12_bollinger_liesTwoPopulationDeviationsAroundTheMean() {
        BollingerBands bands = new BollingerBands(2, 2);

        assertThat(bands.update(1)).isNull();
        assertThat(bands.update(3)).isEqualTo(new BollingerBands.Reading(4, 2, 0));
        assertThat(bands.update(5)).isEqualTo(new BollingerBands.Reading(6, 4, 2));
    }

    /**
     * Periods 2, 3 and 2 over 1, 2, 3, 4, 5: the line starts with the slow EMA at the third close (2.5 − 2 = 0.5) and
     * the signal one close later, seeded with the mean of two lines.
     */
    @Test
    void BR12_macd_startsWithTheSlowEmaAndItsSignalAfterIt() {
        Macd macd = new Macd(2, 3, 2);

        assertThat(macd.update(1)).isNull();
        assertThat(macd.update(2)).isNull();
        Macd.Reading third = macd.update(3);
        assertThat(third.line()).isCloseTo(0.5, within(1e-12));
        assertThat(third.signal()).isEmpty();
        assertThat(third.histogram()).isEmpty();
        Macd.Reading fourth = macd.update(4);
        assertThat(fourth.line()).isCloseTo(0.5, within(1e-12));
        assertThat(fourth.signal().getAsDouble()).isCloseTo(0.5, within(1e-12));
        assertThat(fourth.histogram().getAsDouble()).isCloseTo(0, within(1e-12));
    }

    @Test
    void BR12_aWindowOfOneValue_isThatValueWithNoDeviation() {
        RollingWindow window = new RollingWindow(1);
        window.add(7);
        window.add(9);

        assertThat(window.isFull()).isTrue();
        assertThat(window.size()).isEqualTo(1);
        assertThat(window.sum()).isEqualTo(9);
        assertThat(window.mean()).isEqualTo(9);
        assertThat(window.populationStandardDeviation()).isZero();
    }

    @Test
    void BR12_aCalculatorNeedsAUsablePeriod() {
        assertThatThrownBy(() -> new RollingWindow(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SimpleMovingAverage(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialMovingAverage(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WilderRsi(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Macd(26, 12, 9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Macd(12, 12, 9)).isInstanceOf(IllegalArgumentException.class);
    }
}
