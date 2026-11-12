package com.cryptopilot.journal.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The figures derived from the learning data and never stored: R-multiple, expectancy, cost of late entry and the
 * excursions in R, each at 8 places, null without their inputs or a positive risk.
 *
 * <p>Rule: BR-42, BR-69, BR-70; D-111 (M4).
 */
class LearningFiguresTest {

    /** Net P/L 25 on a planned risk of 10 is +2.5 R; a loss of 10 is −1 R. */
    @Test
    void BR70_theRMultiple_isTheNetPnlOverThePlannedRisk() {
        assertThat(LearningFigures.rMultiple(new BigDecimal("25"), BigDecimal.TEN))
                .isEqualTo(new BigDecimal("2.50000000"));
        assertThat(LearningFigures.rMultiple(new BigDecimal("-10"), BigDecimal.TEN))
                .isEqualTo(new BigDecimal("-1.00000000"));
    }

    @Test
    void BR70_withoutANetPnlOrAPositiveRisk_thereIsNoRMultiple() {
        assertThat(LearningFigures.rMultiple(null, BigDecimal.TEN)).isNull();
        assertThat(LearningFigures.rMultiple(BigDecimal.ONE, null)).isNull();
        assertThat(LearningFigures.rMultiple(BigDecimal.ONE, BigDecimal.ZERO)).isNull();
    }

    /** Expectancy is the mean of the known R-multiples: (2.5 − 1 + 0.5) / 3 = 0.66666667. */
    @Test
    void BR70_theExpectancy_isTheMeanR_ofTheKnownTrades() {
        assertThat(LearningFigures.expectancy(
                        Arrays.asList(new BigDecimal("2.5"), new BigDecimal("-1"), null, new BigDecimal("0.5"))))
                .isEqualTo(new BigDecimal("0.66666667"));
        assertThat(LearningFigures.expectancy(List.of())).isNull();
    }

    /** The cost of late entry is the sum of the deviations: 0.2 + 0.4 − 0.1 = 0.5 R. */
    @Test
    void BR69_theCostOfLateEntry_isTheSumOfTheDeviations() {
        assertThat(LearningFigures.costOfLateEntry(
                        Arrays.asList(new BigDecimal("0.2"), new BigDecimal("0.4"), null, new BigDecimal("-0.1"))))
                .isEqualTo(new BigDecimal("0.50000000"));
        assertThat(LearningFigures.costOfLateEntry(List.of())).isEqualTo(new BigDecimal("0E-8"));
    }

    /** LONG 2 at 100, risk 10: down to 97 is 3 × 2 / 10 = 0.6 R adverse; up to 108 is 1.6 R favourable. */
    @Test
    void BR70_theExcursionsOfALong_areInR() {
        assertThat(LearningFigures.maeR(Direction.LONG, hundred(), new BigDecimal("97"), two(), BigDecimal.TEN))
                .isEqualTo(new BigDecimal("0.60000000"));
        assertThat(LearningFigures.mfeR(Direction.LONG, hundred(), new BigDecimal("108"), two(), BigDecimal.TEN))
                .isEqualTo(new BigDecimal("1.60000000"));
    }

    /** SHORT 2 at 100, risk 10: up to 104 is 0.8 R adverse; down to 95 is 1 R favourable. */
    @Test
    void BR70_theExcursionsOfAShort_areMirrored() {
        assertThat(LearningFigures.maeR(Direction.SHORT, hundred(), new BigDecimal("104"), two(), BigDecimal.TEN))
                .isEqualTo(new BigDecimal("0.80000000"));
        assertThat(LearningFigures.mfeR(Direction.SHORT, hundred(), new BigDecimal("95"), two(), BigDecimal.TEN))
                .isEqualTo(new BigDecimal("1.00000000"));
    }

    @Test
    void BR70_withoutAnExtremeOrARisk_thereIsNoExcursion() {
        assertThat(LearningFigures.maeR(Direction.LONG, hundred(), null, two(), BigDecimal.TEN))
                .isNull();
        assertThat(LearningFigures.mfeR(Direction.LONG, hundred(), null, two(), BigDecimal.TEN))
                .isNull();
        assertThat(LearningFigures.maeR(Direction.LONG, hundred(), new BigDecimal("97"), two(), null))
                .isNull();
    }

    private static BigDecimal hundred() {
        return new BigDecimal("100");
    }

    private static BigDecimal two() {
        return new BigDecimal("2");
    }
}
