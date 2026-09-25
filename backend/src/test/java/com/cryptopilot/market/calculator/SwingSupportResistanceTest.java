package com.cryptopilot.market.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.market.calculator.SwingSupportResistance.Levels;
import com.cryptopilot.market.model.StoredCandle;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Swing support and resistance on series short enough to find the swings by eye.
 *
 * <p>Rule: BR-14; TECHNICAL_DESIGN 7.3.
 */
class SwingSupportResistanceTest {

    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final BigDecimal TWO = new BigDecimal("2");

    /** 9.2 at n−2 is lower than its three known neighbours and nearer the close than 8, but is not confirmed. */
    @Test
    void BR14_swingInTheLastTwoCandles_isIgnored() {
        List<StoredCandle> candles = byLows("9.5", "10", "9", "8", "9", "10", "11", "9.2", "9.4");

        assertThat(SwingSupportResistance.nearest(candles).support()).isEqualByComparingTo("8");
    }

    @Test
    void BR14_lowestLowInTheLastTwoCandles_isNotSupport() {
        List<StoredCandle> candles = byLows("9", "10", "11", "12", "13", "14", "7", "8.5");

        assertThat(SwingSupportResistance.nearest(candles).support()).isNull();
    }

    @Test
    void BR14_highestHighInTheLastTwoCandles_isNotResistance() {
        List<StoredCandle> candles = byHighs("14", "14", "13", "12", "11", "10", "20", "15");

        assertThat(SwingSupportResistance.nearest(candles).resistance()).isNull();
    }

    @Test
    void BR14_equalLows_areNotSwings() {
        List<StoredCandle> candles = byLows("12.5", "10", "9", "8", "8.00", "9", "10", "11", "12");

        assertThat(SwingSupportResistance.nearest(candles).support()).isNull();
    }

    @Test
    void BR14_lowEqualToASecondNeighbour_isNotASwing() {
        List<StoredCandle> candles = byLows("12.5", "8", "9", "8", "9", "10", "11", "12");

        assertThat(SwingSupportResistance.nearest(candles).support()).isNull();
    }

    @Test
    void BR14_equalHighs_areNotSwings() {
        List<StoredCandle> candles = byHighs("7.5", "10", "11", "12", "12.0", "11", "10", "9", "8");

        assertThat(SwingSupportResistance.nearest(candles).resistance()).isNull();
    }

    @Test
    void BR14_severalSwingLowsBelowTheClose_givesTheHighest() {
        List<StoredCandle> nearestLast = byLows("17", "20", "19", "10", "19", "20", "19", "14", "19", "20", "16", "17");
        List<StoredCandle> nearestFirst =
                byLows("17", "20", "19", "14", "19", "20", "19", "10", "19", "20", "16", "17");

        assertThat(SwingSupportResistance.nearest(nearestLast).support()).isEqualByComparingTo("14");
        assertThat(SwingSupportResistance.nearest(nearestFirst).support()).isEqualByComparingTo("14");
    }

    @Test
    void BR14_severalSwingHighsAboveTheClose_givesTheLowest() {
        List<StoredCandle> nearestLast =
                byHighs("13", "10", "11", "18", "11", "10", "11", "16", "11", "10", "12", "13");
        List<StoredCandle> nearestFirst =
                byHighs("13", "10", "11", "16", "11", "10", "11", "18", "11", "10", "12", "13");

        assertThat(SwingSupportResistance.nearest(nearestLast).resistance()).isEqualByComparingTo("16");
        assertThat(SwingSupportResistance.nearest(nearestFirst).resistance()).isEqualByComparingTo("16");
    }

    @Test
    void BR14_swingLowAboveTheClose_isNotSupport() {
        List<StoredCandle> candles = byLows("17", "20", "19", "18", "19", "20", "19", "14", "19", "20", "16", "15");

        assertThat(SwingSupportResistance.nearest(candles).support()).isEqualByComparingTo("14");
    }

    @Test
    void BR14_swingHighBelowTheClose_isNotResistance() {
        List<StoredCandle> candles = byHighs("13", "10", "11", "12", "11", "10", "11", "16", "11", "10", "14", "15");

        assertThat(SwingSupportResistance.nearest(candles).resistance()).isEqualByComparingTo("16");
    }

    @Test
    void BR14_swingAtTheClose_isNeitherLevel() {
        assertThat(SwingSupportResistance.nearest(byLows("8", "10", "9", "8", "9", "10"))
                        .support())
                .isNull();
        assertThat(SwingSupportResistance.nearest(byHighs("12", "10", "11", "12", "11", "10"))
                        .resistance())
                .isNull();
    }

    @Test
    void BR14_bothLevelsFound_inOneSeries() {
        List<StoredCandle> candles = List.of(
                candle(0, "10", "14", "12"),
                candle(1, "9", "15", "12"),
                candle(2, "8", "13", "12"),
                candle(3, "9", "12", "12"),
                candle(4, "10", "13", "12"),
                candle(5, "11", "16", "12"),
                candle(6, "11", "14", "12"),
                candle(7, "11", "13", "11.5"));

        Levels levels = SwingSupportResistance.nearest(candles);

        assertThat(levels.support()).isEqualByComparingTo("8");
        assertThat(levels.resistance()).isEqualByComparingTo("16");
    }

    @Test
    void BR14_noSwing_givesNullLevels() {
        List<StoredCandle> candles = byLows("15", "10", "11", "12", "13", "14", "15", "16");

        assertThat(SwingSupportResistance.nearest(candles)).isEqualTo(new Levels(null, null));
    }

    @Test
    void BR14_fewerThanFiveCandles_givesNullLevels() {
        assertThat(SwingSupportResistance.nearest(List.of())).isEqualTo(new Levels(null, null));
        assertThat(SwingSupportResistance.nearest(byLows("12", "10", "9", "8", "9")))
                .isEqualTo(new Levels(null, null));
    }

    @Test
    void BR14_fiveCandles_confirmTheMiddleOne() {
        assertThat(SwingSupportResistance.nearest(byLows("12", "10", "9", "8", "9", "10"))
                        .support())
                .isEqualByComparingTo("8");
    }

    /** The swing low 5 sits at index 3: at 201 candles it keeps both left neighbours in the window, at 202 it does not. */
    @Test
    void BR14_swingOutsideTheLast200Candles_isIgnored() {
        assertThat(SwingSupportResistance.nearest(windowSeries(201)).support()).isEqualByComparingTo("5");
        assertThat(SwingSupportResistance.nearest(windowSeries(202)).support()).isNull();
        assertThat(SwingSupportResistance.nearest(windowSeries(204)).support()).isNull();
    }

    @Test
    void BR14_nullSeries_isRefused() {
        assertThatThrownBy(() -> SwingSupportResistance.nearest(null)).isInstanceOf(NullPointerException.class);
    }

    private static List<StoredCandle> windowSeries(int size) {
        List<String> lows = new ArrayList<>(List.of("8", "7", "6", "5", "6", "7"));
        lows.addAll(Collections.nCopies(size - lows.size(), "8"));
        return byLows("9", lows.toArray(String[]::new));
    }

    /** Candles with the given lows and flat highs, the last one closing at {@code close}. */
    private static List<StoredCandle> byLows(String close, String... lows) {
        List<StoredCandle> candles = new ArrayList<>();
        for (int i = 0; i < lows.length; i++) {
            candles.add(candle(i, lows[i], "100", i == lows.length - 1 ? close : lows[i]));
        }
        return candles;
    }

    /** Candles with the given highs and flat lows, the last one closing at {@code close}. */
    private static List<StoredCandle> byHighs(String close, String... highs) {
        List<StoredCandle> candles = new ArrayList<>();
        for (int i = 0; i < highs.length; i++) {
            candles.add(candle(i, "0", highs[i], i == highs.length - 1 ? close : highs[i]));
        }
        return candles;
    }

    private static StoredCandle candle(int index, String low, String high, String close) {
        Instant open = START.plus(Duration.ofHours(index));
        BigDecimal closePrice = new BigDecimal(close);
        return new StoredCandle(
                open,
                open.plus(Duration.ofHours(1)).minusMillis(1),
                closePrice,
                new BigDecimal(high),
                new BigDecimal(low),
                closePrice,
                TWO,
                TWO,
                null);
    }
}
