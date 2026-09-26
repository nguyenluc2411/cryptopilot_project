package com.cryptopilot.market.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.model.ComponentInputs;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.DerivativesInputs;
import com.cryptopilot.market.model.DominantSide;
import com.cryptopilot.market.model.IndicatorSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The component scores on inputs chosen so each reading can be checked by hand.
 *
 * <p>Rule: BR-13, BR-21; TECHNICAL_DESIGN 7.4; D-53.
 */
class SetupComponentsTest {

    private static final BigDecimal VOLUME = new BigDecimal("100");

    /** TECHNICAL_DESIGN 7.4 worked example: one strong down-trend, close just under resistance. */
    @Test
    void BR13_workedExample_givesLowSpotAndHighFuturesReadings() {
        IndicatorSnapshot down = indicators(95.0, 100.0, 110.0, 35.0, -0.8, 100.0);

        ComponentScores spot = SetupComponents.compute(inputs(MarketType.SPOT, "90", down, -0.5, "70", "92", null));
        ComponentScores futures =
                SetupComponents.compute(inputs(MarketType.FUTURES, "90", down, -0.5, "70", "92", derivatives()));

        assertThat(spot.trend()).isEqualByComparingTo("0");
        assertThat(spot.momentum()).isEqualByComparingTo("0");
        assertThat(spot.level()).isEqualByComparingTo("3.33");
        assertThat(futures.trend()).isEqualByComparingTo("100");
        assertThat(futures.momentum()).isEqualByComparingTo("100");
        assertThat(futures.level()).isEqualByComparingTo("100");
        assertThat(SetupComponents.dominantSide(MarketType.FUTURES, new BigDecimal("90"), down))
                .isEqualTo(DominantSide.SHORT);
    }

    @Test
    void BR13_spotStrongUpTrend_givesFullTrendAndMomentum() {
        IndicatorSnapshot up = indicators(105.0, 100.0, 90.0, 60.0, 1.0, 100.0);

        ComponentScores spot = SetupComponents.compute(inputs(MarketType.SPOT, "110", up, 0.5, null, null, null));

        assertThat(spot.trend()).isEqualByComparingTo("100");
        assertThat(spot.momentum()).isEqualByComparingTo("100");
    }

    /** close 100 near support 99, resistance 103: q = 3 on the LONG side, the top of the scale. */
    @Test
    void BR13_spotCloseNearSupport_givesFullLevel_andNearResistanceGivesLow() {
        IndicatorSnapshot flat = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0);

        assertThat(SetupComponents.compute(inputs(MarketType.SPOT, "100", flat, 0.0, "99", "103", null))
                        .level())
                .isEqualByComparingTo("100");
        assertThat(SetupComponents.compute(inputs(MarketType.SPOT, "100", flat, 0.0, "97", "101", null))
                        .level())
                .isEqualByComparingTo("11.11");
        assertThat(SetupComponents.compute(inputs(MarketType.FUTURES, "100", flat, 0.0, "97", "101", derivatives()))
                        .level())
                .isEqualByComparingTo("100");
    }

    @Test
    void BR13_missingSupportOrResistance_givesMidLevel() {
        IndicatorSnapshot flat = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0);

        assertThat(SetupComponents.compute(inputs(MarketType.SPOT, "100", flat, 0.0, null, "103", null))
                        .level())
                .isEqualByComparingTo("50");
        assertThat(SetupComponents.compute(inputs(MarketType.FUTURES, "100", flat, 0.0, "99", null, derivatives()))
                        .level())
                .isEqualByComparingTo("50");
    }

    @Test
    void BR13_levelsOnTheWrongSideOfTheClose_areRefused() {
        IndicatorSnapshot flat = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0);

        assertThatThrownBy(() -> SetupComponents.compute(inputs(MarketType.SPOT, "100", flat, 0.0, "100", "103", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SetupComponents.compute(inputs(MarketType.SPOT, "100", flat, 0.0, "99", "100", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void BR13_futuresUpTrend_isLongDominant() {
        IndicatorSnapshot up = indicators(105.0, 100.0, 90.0, 60.0, 1.0, 100.0);

        assertThat(SetupComponents.dominantSide(MarketType.FUTURES, new BigDecimal("110"), up))
                .isEqualTo(DominantSide.LONG);
    }

    /** Signs +, −, +, − over close−EMA20, EMA20−EMA50, EMA50−EMA200, close−EMA200: two conditions each side. */
    @Test
    void BR13_futuresBalancedTrend_isNeutralAndScoresHalf() {
        IndicatorSnapshot mixed = indicators(90.0, 110.0, 105.0, 50.0, 0.0, 100.0);
        BigDecimal close = new BigDecimal("100");

        assertThat(SetupComponents.dominantSide(MarketType.FUTURES, close, mixed))
                .isEqualTo(DominantSide.NEUTRAL);
        assertThat(SetupComponents.compute(inputs(MarketType.FUTURES, "100", mixed, 0.0, null, null, derivatives()))
                        .trend())
                .isEqualByComparingTo("50");
    }

    @Test
    void BR13_equalEmas_holdNoConditionAndAreNeutral() {
        IndicatorSnapshot flat = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0);

        assertThat(SetupComponents.dominantSide(MarketType.FUTURES, new BigDecimal("100"), flat))
                .isEqualTo(DominantSide.NEUTRAL);
        assertThat(SetupComponents.compute(inputs(MarketType.FUTURES, "100", flat, 0.0, null, null, derivatives()))
                        .trend())
                .isEqualByComparingTo("0");
    }

    @Test
    void BR13_spot_hasNoDominantSide() {
        IndicatorSnapshot up = indicators(105.0, 100.0, 90.0, 60.0, 1.0, 100.0);

        assertThat(SetupComponents.dominantSide(MarketType.SPOT, new BigDecimal("110"), up))
                .isNull();
    }

    @Test
    void BR13_anyMissingEma_leavesTrendNull() {
        assertThat(trend(indicators(null, 100.0, 90.0, 60.0, 1.0, 100.0))).isNull();
        assertThat(trend(indicators(105.0, null, 90.0, 60.0, 1.0, 100.0))).isNull();
        assertThat(trend(indicators(105.0, 100.0, null, 60.0, 1.0, 100.0))).isNull();
    }

    @Test
    void BR13_missingEma_leavesTrendAndDominantSideNull() {
        IndicatorSnapshot warmingUp = indicators(105.0, 100.0, null, 60.0, 1.0, 100.0);

        assertThat(SetupComponents.compute(inputs(MarketType.FUTURES, "110", warmingUp, 0.5, null, null, derivatives()))
                        .trend())
                .isNull();
        assertThat(SetupComponents.dominantSide(MarketType.FUTURES, new BigDecimal("110"), warmingUp))
                .isNull();
    }

    @Test
    void BR13_missingRsiHistogramOrPreviousHistogram_leavesMomentumNull() {
        assertThat(momentum(indicators(100.0, 100.0, 100.0, null, 1.0, 100.0), 0.5))
                .isNull();
        assertThat(momentum(indicators(100.0, 100.0, 100.0, 60.0, null, 100.0), 0.5))
                .isNull();
        assertThat(momentum(indicators(100.0, 100.0, 100.0, 60.0, 1.0, 100.0), null))
                .isNull();
    }

    @ParameterizedTest(name = "RSI {0}: LONG {1}, SHORT {2}")
    @CsvSource({
        "19.99, 0, 0",
        "20, 0, 25",
        "29.99, 0, 25",
        "30, 0, 50",
        "39.99, 0, 50",
        "40, 25, 50",
        "49.99, 25, 50",
        "50, 50, 50",
        "50.01, 50, 25",
        "60, 50, 25",
        "60.01, 50, 0",
        "70, 50, 0",
        "70.01, 25, 0",
        "80, 25, 0",
        "80.01, 0, 0"
    })
    void BR13_rsiPart_followsTheOneSidedTables(double rsi, int longPart, int shortPart) {
        assertThat(SetupComponents.rsiPart(rsi, SetupComponents.LONG)).isEqualTo(longPart);
        assertThat(SetupComponents.rsiPart(rsi, SetupComponents.SHORT)).isEqualTo(shortPart);
    }

    /** RSI 90 scores nothing either way, so only the MACD part moves. */
    @ParameterizedTest(name = "histogram {0} after {1}: Spot {2}, Futures {3}")
    @CsvSource({
        "1.0, 0.5, 50, 50",
        "1.0, 1.5, 30, 30",
        "1.0, 1.0, 30, 30",
        "-1.0, -0.5, 0, 50",
        "-1.0, -1.5, 20, 30",
        "0.0, 0.0, 0, 0"
    })
    void BR13_macdPart_rewardsTheSideTheHistogramAndItsChangeFavour(
            double histogram, double previous, int spot, int futures) {
        IndicatorSnapshot ind = indicators(100.0, 100.0, 100.0, 90.0, histogram, 100.0);

        assertThat(SetupComponents.compute(inputs(MarketType.SPOT, "100", ind, previous, null, null, null))
                        .momentum())
                .isEqualByComparingTo(String.valueOf(spot));
        assertThat(SetupComponents.compute(inputs(MarketType.FUTURES, "100", ind, previous, null, null, derivatives()))
                        .momentum())
                .isEqualByComparingTo(String.valueOf(futures));
    }

    @ParameterizedTest(name = "volume {0} over an average of 100 scores {1}")
    @CsvSource({"20, 0", "50, 0", "100, 50", "150, 100", "300, 100", "75, 25"})
    void BR13_volume_isTheClampedRatioOverItsAverage(String volume, String expected) {
        IndicatorSnapshot ind = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0);
        ComponentInputs in = new ComponentInputs(
                MarketType.SPOT, "1h", new BigDecimal("100"), new BigDecimal(volume), ind, 0.0, null, null, null);

        assertThat(SetupComponents.compute(in).volume()).isEqualByComparingTo(expected);
    }

    @Test
    void BR13_missingOrZeroVolumeAverage_leavesVolumeNull() {
        assertThat(SetupComponents.compute(inputs(
                                MarketType.SPOT,
                                "100",
                                indicators(100.0, 100.0, 100.0, 50.0, 0.0, null),
                                0.0,
                                null,
                                null,
                                null))
                        .volume())
                .isNull();
        assertThat(SetupComponents.compute(inputs(
                                MarketType.SPOT,
                                "100",
                                indicators(100.0, 100.0, 100.0, 50.0, 0.0, 0.0),
                                0.0,
                                null,
                                null,
                                null))
                        .volume())
                .isNull();
    }

    @ParameterizedTest(name = "funding {0}, Δprice {1}, ΔOI {2}: {3}")
    @CsvSource({
        "0.0005, 1, 1, 100",
        "-0.0005, -1, 1, 100",
        "0.0010, 1, 1, 100",
        "0, 1, 1, 75",
        "0, 1, 0, 50",
        "0, -1, -1, 50",
        "0, 0, 1, 25",
        "0.00025, 0, 0, 37.50"
    })
    void BR13_derivatives_addTheFundingAndOpenInterestParts(
            String funding, String priceChange, String oiChange, String expected) {
        DerivativesInputs d =
                new DerivativesInputs(new BigDecimal(funding), new BigDecimal(priceChange), new BigDecimal(oiChange));
        IndicatorSnapshot ind = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0);

        assertThat(SetupComponents.compute(inputs(MarketType.FUTURES, "100", ind, 0.0, null, null, d))
                        .derivatives())
                .isEqualByComparingTo(expected);
    }

    @Test
    void BR13_missingDerivativesData_leavesDerivativesNull_andSpotNeverHasOne() {
        IndicatorSnapshot ind = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0);

        assertThat(SetupComponents.compute(inputs(MarketType.FUTURES, "100", ind, 0.0, null, null, null))
                        .derivatives())
                .isNull();
        assertThat(SetupComponents.compute(inputs(
                                MarketType.FUTURES,
                                "100",
                                ind,
                                0.0,
                                null,
                                null,
                                new DerivativesInputs(null, BigDecimal.ONE, BigDecimal.ONE)))
                        .derivatives())
                .isNull();
        assertThat(SetupComponents.compute(inputs(
                                MarketType.FUTURES,
                                "100",
                                ind,
                                0.0,
                                null,
                                null,
                                new DerivativesInputs(BigDecimal.ZERO, null, BigDecimal.ONE)))
                        .derivatives())
                .isNull();
        assertThat(SetupComponents.compute(inputs(
                                MarketType.FUTURES,
                                "100",
                                ind,
                                0.0,
                                null,
                                null,
                                new DerivativesInputs(BigDecimal.ZERO, BigDecimal.ONE, null)))
                        .derivatives())
                .isNull();
        assertThat(SetupComponents.compute(inputs(MarketType.SPOT, "100", ind, 0.0, null, null, derivatives()))
                        .derivatives())
                .isNull();
    }

    @Test
    void BR13_scores_carryTheSeriesAndTheFormulaVersion() {
        ComponentScores scores = SetupComponents.compute(inputs(
                MarketType.FUTURES,
                "100",
                indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0),
                0.0,
                null,
                null,
                derivatives()));

        assertThat(scores.market()).isEqualTo(MarketType.FUTURES);
        assertThat(scores.timeframe()).isEqualTo("1h");
        assertThat(scores.formulaVersion()).isEqualTo("v1");
    }

    private static BigDecimal trend(IndicatorSnapshot ind) {
        return SetupComponents.compute(inputs(MarketType.SPOT, "110", ind, 0.5, null, null, null))
                .trend();
    }

    private static BigDecimal momentum(IndicatorSnapshot ind, Double previous) {
        return SetupComponents.compute(inputs(MarketType.SPOT, "100", ind, previous, null, null, null))
                .momentum();
    }

    private static DerivativesInputs derivatives() {
        return new DerivativesInputs(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE);
    }

    private static ComponentInputs inputs(
            MarketType market,
            String close,
            IndicatorSnapshot ind,
            Double previousHistogram,
            String support,
            String resistance,
            DerivativesInputs derivatives) {
        return new ComponentInputs(
                market,
                "1h",
                new BigDecimal(close),
                VOLUME,
                ind,
                previousHistogram,
                support == null ? null : new BigDecimal(support),
                resistance == null ? null : new BigDecimal(resistance),
                derivatives);
    }

    static IndicatorSnapshot indicators(
            Double ema20, Double ema50, Double ema200, Double rsi, Double histogram, Double volumeSma20) {
        return new IndicatorSnapshot(
                Instant.parse("2026-09-01T00:00:00Z"),
                200,
                null,
                ema20,
                ema50,
                ema200,
                rsi,
                null,
                null,
                histogram,
                null,
                null,
                null,
                volumeSma20);
    }
}
