package com.cryptopilot.market.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.cryptopilot.market.SetupScoreTunings;
import com.cryptopilot.market.model.ComponentInputs;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.DerivativesInputs;
import com.cryptopilot.market.model.IndicatorSnapshot;
import com.cryptopilot.market.model.enums.DominantSide;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.ScoredSide;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The component scores v2 on inputs chosen so each reading can be checked by hand.
 *
 * <p>Rule: BR-13, BR-21; TECHNICAL_DESIGN 7.4; D-53.
 */
class SetupComponentsTest {

    private static final BigDecimal VOLUME = new BigDecimal("100");

    /** TECHNICAL_DESIGN 7.4 worked example: one strong down-trend, close just under resistance. */
    @Test
    void BR13_workedExample_givesLowSpotAndHighFuturesReadings() {
        IndicatorSnapshot down = indicators(95.0, 100.0, 110.0, 35.0, -0.8, 100.0);

        ComponentScores spot = compute(inputs(MarketType.SPOT, "90", down, -0.5, "70", "92", null));
        ComponentScores futures = compute(inputs(MarketType.FUTURES, "90", down, -0.5, "70", "92", derivatives()));

        assertThat(spot.trend()).isEqualByComparingTo("0");
        assertThat(spot.momentum()).isEqualByComparingTo("0");
        assertThat(spot.level()).isEqualByComparingTo("3.33");
        assertThat(spot.scoredSide()).isEqualTo(ScoredSide.LONG);
        assertThat(futures.trend()).isEqualByComparingTo("100");
        assertThat(futures.momentum()).isEqualByComparingTo("100");
        assertThat(futures.level()).isEqualByComparingTo("100");
        assertThat(futures.scoredSide()).isEqualTo(ScoredSide.SHORT);
        assertThat(SetupComponents.dominantSide(MarketType.FUTURES, new BigDecimal("90"), down))
                .isEqualTo(DominantSide.SHORT);
    }

    @Test
    void BR13_spotStrongUpTrend_givesFullTrendAndMomentum() {
        IndicatorSnapshot up = indicators(105.0, 100.0, 90.0, 60.0, 1.0, 100.0);

        ComponentScores spot = compute(inputs(MarketType.SPOT, "110", up, 0.5, null, null, null));

        assertThat(spot.trend()).isEqualByComparingTo("100");
        assertThat(spot.momentum()).isEqualByComparingTo("100");
    }

    /** close 100 near support 99, resistance 103: q = 3 on the LONG side, the top of the scale. */
    @Test
    void BR13_closeNearSupport_givesFullLevel_andNearResistanceGivesLow_onBothMarkets() {
        IndicatorSnapshot flat = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0);

        assertThat(compute(inputs(MarketType.SPOT, "100", flat, 0.0, "99", "103", null))
                        .level())
                .isEqualByComparingTo("100");
        assertThat(compute(inputs(MarketType.SPOT, "100", flat, 0.0, "97", "101", null))
                        .level())
                .isEqualByComparingTo("11.11");
        // v2: one scored side (LONG here), so Futures no longer takes the better of the two sides (v1 gave 100).
        assertThat(compute(inputs(MarketType.FUTURES, "100", flat, 0.0, "97", "101", derivatives()))
                        .level())
                .isEqualByComparingTo("11.11");
    }

    @Test
    void BR13_missingSupportOrResistance_givesMidLevel() {
        IndicatorSnapshot flat = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0);

        assertThat(compute(inputs(MarketType.SPOT, "100", flat, 0.0, null, "103", null))
                        .level())
                .isEqualByComparingTo("50");
        assertThat(compute(inputs(MarketType.FUTURES, "100", flat, 0.0, "99", null, derivatives()))
                        .level())
                .isEqualByComparingTo("50");
    }

    @Test
    void BR13_levelsOnTheWrongSideOfTheClose_areRefused() {
        IndicatorSnapshot flat = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0);

        assertThatThrownBy(() -> compute(inputs(MarketType.SPOT, "100", flat, 0.0, "100", "103", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compute(inputs(MarketType.SPOT, "100", flat, 0.0, "99", "100", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** support 0.01 under the close, resistance 0.5 over it, σ 2: the risk is read as at least 0.5σ = 1. */
    @Test
    void BR13_tightSupport_isGuardedByHalfASigma_soLevelStaysBounded() {
        IndicatorSnapshot withSigma = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0, 2.0);
        IndicatorSnapshot noSigma = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0);

        // q = 0.5 / max(0.01, 1) = 0.5 → 100·0.5/3
        assertThat(compute(inputs(MarketType.SPOT, "100", withSigma, 0.0, "99.99", "100.5", null))
                        .level())
                .isEqualByComparingTo("16.67");
        // no σ, no guard: q = 0.5 / 0.01 = 50, clamped
        assertThat(compute(inputs(MarketType.SPOT, "100", noSigma, 0.0, "99.99", "100.5", null))
                        .level())
                .isEqualByComparingTo("100");
    }

    @Test
    void BR13_futuresUpTrend_isLongDominant() {
        IndicatorSnapshot up = indicators(105.0, 100.0, 90.0, 60.0, 1.0, 100.0);

        assertThat(SetupComponents.dominantSide(MarketType.FUTURES, new BigDecimal("110"), up))
                .isEqualTo(DominantSide.LONG);
    }

    /** close 110 just under resistance 111 with support 100: little room for the LONG side the trend points to. */
    @Test
    void BR13_futuresUpTrendAtResistance_scoresTheLongLevelLow() {
        IndicatorSnapshot up = indicators(105.0, 100.0, 90.0, 60.0, 1.0, 100.0);

        ComponentScores futures = compute(inputs(MarketType.FUTURES, "110", up, 0.5, "100", "111", derivatives()));

        assertThat(futures.scoredSide()).isEqualTo(ScoredSide.LONG);
        assertThat(futures.level()).isEqualByComparingTo("3.33");
    }

    /** Signs +, −, +, − over close−EMA20, EMA20−EMA50, EMA50−EMA200, close−EMA200: two conditions each side. */
    @Test
    void BR13_futuresBalancedTrend_isNeutral_andTheHistogramSignPicksTheSide() {
        IndicatorSnapshot rising = indicators(90.0, 110.0, 105.0, 50.0, 0.0, 100.0);
        IndicatorSnapshot falling = indicators(90.0, 110.0, 105.0, 50.0, -0.1, 100.0);
        BigDecimal close = new BigDecimal("100");

        assertThat(SetupComponents.dominantSide(MarketType.FUTURES, close, rising))
                .isEqualTo(DominantSide.NEUTRAL);
        ComponentScores longSide = compute(inputs(MarketType.FUTURES, "100", rising, 0.0, null, null, derivatives()));
        ComponentScores shortSide = compute(inputs(MarketType.FUTURES, "100", falling, 0.0, null, null, derivatives()));
        assertThat(longSide.scoredSide()).isEqualTo(ScoredSide.LONG);
        assertThat(shortSide.scoredSide()).isEqualTo(ScoredSide.SHORT);
        assertThat(longSide.trend()).isEqualByComparingTo("50");
        assertThat(shortSide.trend()).isEqualByComparingTo("50");
    }

    @Test
    void BR13_equalEmas_holdNoConditionAndAreNeutral() {
        IndicatorSnapshot flat = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0);

        assertThat(SetupComponents.dominantSide(MarketType.FUTURES, new BigDecimal("100"), flat))
                .isEqualTo(DominantSide.NEUTRAL);
        assertThat(compute(inputs(MarketType.FUTURES, "100", flat, 0.0, null, null, derivatives()))
                        .trend())
                .isEqualByComparingTo("0");
    }

    /** σ 1, band 0.1: every difference of this up-trend is under 0.1, so none holds (with no band all four would). */
    @Test
    void BR13_trendDifferencesInsideTheBand_holdNoCondition() {
        IndicatorSnapshot narrow = indicators(100.05, 100.0, 99.99, 60.0, 1.0, 100.0, 1.0);
        IndicatorSnapshot noSigma = indicators(100.05, 100.0, 99.99, 60.0, 1.0, 100.0);

        assertThat(compute(inputs(MarketType.SPOT, "100.06", narrow, 0.5, null, null, null))
                        .trend())
                .isEqualByComparingTo("0");
        assertThat(compute(inputs(MarketType.SPOT, "100.06", noSigma, 0.5, null, null, null))
                        .trend())
                .isEqualByComparingTo("100");
    }

    /** The floors of v1 (trend by max of sides, level ≥ 33.3, derivatives ≥ 25 on Futures) are gone. */
    @Test
    void BR13_futuresComponents_canFallBelowTheV1Floors() {
        IndicatorSnapshot narrow = indicators(100.05, 100.0, 99.99, 60.0, 1.0, 100.0, 1.0);
        DerivativesInputs crowdedLongFalling =
                new DerivativesInputs(new BigDecimal("0.0006"), BigDecimal.ONE.negate(), BigDecimal.ONE);

        ComponentScores futures =
                compute(inputs(MarketType.FUTURES, "100.06", narrow, 0.5, "90", "100.4", crowdedLongFalling));

        assertThat(futures.scoredSide()).isEqualTo(ScoredSide.LONG);
        assertThat(futures.trend()).isEqualByComparingTo("0");
        assertThat(futures.level()).isLessThan(new BigDecimal("33.3"));
        assertThat(futures.derivatives()).isLessThan(new BigDecimal("25"));
    }

    @Test
    void BR21_spotAndFutures_scoreTheSameLongInputsAlike() {
        IndicatorSnapshot up = indicators(105.0, 100.0, 90.0, 62.0, 1.0, 100.0, 2.0);

        ComponentScores spot = compute(inputs(MarketType.SPOT, "110", up, 0.5, "104", "118", null, "108", "150"));
        ComponentScores futures =
                compute(inputs(MarketType.FUTURES, "110", up, 0.5, "104", "118", derivatives(), "108", "150"));

        assertThat(futures.scoredSide()).isEqualTo(ScoredSide.LONG);
        assertThat(futures.trend()).isEqualByComparingTo(spot.trend());
        assertThat(futures.momentum()).isEqualByComparingTo(spot.momentum());
        assertThat(futures.volume()).isEqualByComparingTo(spot.volume());
        assertThat(futures.level()).isEqualByComparingTo(spot.level());
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

        assertThat(compute(inputs(MarketType.FUTURES, "110", warmingUp, 0.5, null, null, derivatives()))
                        .trend())
                .isNull();
        assertThat(SetupComponents.dominantSide(MarketType.FUTURES, new BigDecimal("110"), warmingUp))
                .isNull();
    }

    /** No readable trend and no histogram: no side can be chosen, so no component is scored. */
    @Test
    void BR13_futuresWithNoSide_scoresNoComponent() {
        IndicatorSnapshot early = indicators(105.0, 100.0, null, 60.0, null, 100.0);

        ComponentScores futures = compute(inputs(MarketType.FUTURES, "110", early, null, "100", "120", derivatives()));

        assertThat(futures.scoredSide()).isNull();
        assertThat(futures.volume()).isNull();
        assertThat(futures.level()).isNull();
        assertThat(futures.derivatives()).isNull();
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
        "20, 0, 0",
        "27.5, 0, 25",
        "35, 0, 50",
        "39.99, 0, 50",
        "40, 0, 50",
        "45, 25, 50",
        "50, 50, 50",
        "55, 50, 25",
        "60, 50, 0",
        "65, 50, 0",
        "72.5, 25, 0",
        "80, 0, 0",
        "85, 0, 0"
    })
    void BR13_rsiPart_isPiecewiseLinear_andShortReadsItsMirror(double rsi, double longPart, double shortPart) {
        assertThat(SetupComponents.rsiPart(rsi, SetupComponents.LONG)).isCloseTo(longPart, within(1e-9));
        assertThat(SetupComponents.rsiPart(rsi, SetupComponents.SHORT)).isCloseTo(shortPart, within(1e-9));
    }

    @Test
    void BR13_rsiPart_hasNoStep() {
        for (double rsi = 30; rsi <= 85; rsi += 0.1) {
            assertThat(Math.abs(SetupComponents.rsiPart(rsi + 0.1, SetupComponents.LONG)
                            - SetupComponents.rsiPart(rsi, SetupComponents.LONG)))
                    .isLessThanOrEqualTo(0.5 + 1e-9);
        }
    }

    /** RSI 90 scores nothing either way, so only the MACD part moves; flat EMAs leave the side to the histogram. */
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

        assertThat(compute(inputs(MarketType.SPOT, "100", ind, previous, null, null, null))
                        .momentum())
                .isEqualByComparingTo(String.valueOf(spot));
        assertThat(compute(inputs(MarketType.FUTURES, "100", ind, previous, null, null, derivatives()))
                        .momentum())
                .isEqualByComparingTo(String.valueOf(futures));
    }

    @ParameterizedTest(name = "volume {0} over an average of 100 scores {1}")
    @CsvSource({"20, 0", "50, 0", "87.5, 25", "100, 33.33", "125, 50", "200, 100", "300, 100"})
    void BR13_volume_isTheRatioOverItsAverage_saturatingAtTwice(String volume, String expected) {
        IndicatorSnapshot ind = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0);

        assertThat(compute(inputs(MarketType.SPOT, "100", ind, 0.0, null, null, null, "100", volume))
                        .volume())
                .isEqualByComparingTo(expected);
    }

    @Test
    void BR13_aCandleAgainstTheScoredSide_halvesTheVolume_andAFlatCandleDoesNot() {
        IndicatorSnapshot rising = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0);
        IndicatorSnapshot falling = indicators(100.0, 100.0, 100.0, 50.0, -0.1, 100.0);

        assertThat(compute(inputs(MarketType.SPOT, "100", rising, 0.0, null, null, null, "101", "200"))
                        .volume())
                .isEqualByComparingTo("50");
        assertThat(compute(inputs(MarketType.SPOT, "100", rising, 0.0, null, null, null, "99", "200"))
                        .volume())
                .isEqualByComparingTo("100");
        assertThat(compute(inputs(MarketType.SPOT, "100", rising, 0.0, null, null, null, "100", "200"))
                        .volume())
                .isEqualByComparingTo("100");
        // Futures scored SHORT (flat EMAs, negative histogram): a rising candle is against it.
        assertThat(compute(inputs(MarketType.FUTURES, "100", falling, 0.0, null, null, derivatives(), "99", "200"))
                        .volume())
                .isEqualByComparingTo("50");
    }

    @Test
    void BR13_missingOrZeroVolumeAverage_leavesVolumeNull() {
        assertThat(compute(inputs(
                                MarketType.SPOT,
                                "100",
                                indicators(100.0, 100.0, 100.0, 50.0, 0.0, null),
                                0.0,
                                null,
                                null,
                                null))
                        .volume())
                .isNull();
        assertThat(compute(inputs(
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

    /** Flat EMAs: a histogram of 0 scores LONG, of −0.1 SHORT. */
    @ParameterizedTest(name = "funding {0}, Δprice {1}, ΔOI {2}: LONG {3}, SHORT {4}")
    @CsvSource({
        "0.0001, 1, 1, 75, 25",
        "0.0001, -1, 1, 25, 75",
        "0.0001, 1, -1, 50, 25",
        "0.0001, 0, 1, 50, 50",
        "0.0006, 1, 1, 50, 50",
        "-0.0004, 0, 0, 75, 25",
        "0.00035, 1, 0, 37.50, 37.50",
        "0.0021, 1, 1, 50, 50"
    })
    void BR13_derivatives_addTheFundingAndOpenInterestPartsOfTheScoredSide(
            String funding, String priceChange, String oiChange, String longScore, String shortScore) {
        DerivativesInputs d =
                new DerivativesInputs(new BigDecimal(funding), new BigDecimal(priceChange), new BigDecimal(oiChange));

        assertThat(derivatives(0.0, d)).isEqualByComparingTo(longScore);
        assertThat(derivatives(-0.1, d)).isEqualByComparingTo(shortScore);
    }

    @Test
    void BR13_baselineFunding_givesBothSides25_andHigherFundingFavoursShort() {
        DerivativesInputs baseline = new DerivativesInputs(new BigDecimal("0.0001"), BigDecimal.ZERO, BigDecimal.ZERO);
        DerivativesInputs above = new DerivativesInputs(new BigDecimal("0.0003"), BigDecimal.ZERO, BigDecimal.ZERO);

        // 25 of funding on each side, plus the open interest part, 25 with no price change
        assertThat(derivatives(0.0, baseline)).isEqualByComparingTo("50");
        assertThat(derivatives(-0.1, baseline)).isEqualByComparingTo("50");
        assertThat(derivatives(0.0, above)).isLessThan(derivatives(0.0, baseline));
        assertThat(derivatives(-0.1, above)).isGreaterThan(derivatives(-0.1, baseline));
    }

    @Test
    void BR13_openInterest_followsThePriceMoveRelativeToTheScoredSide() {
        BigDecimal f = new BigDecimal("0.0001");
        DerivativesInputs upWithOi = new DerivativesInputs(f, BigDecimal.ONE, BigDecimal.ONE);
        DerivativesInputs upWithoutOi = new DerivativesInputs(f, BigDecimal.ONE, BigDecimal.ONE.negate());
        DerivativesInputs down = new DerivativesInputs(f, BigDecimal.ONE.negate(), BigDecimal.ONE);

        assertThat(derivatives(0.0, upWithOi)).isEqualByComparingTo("75");
        assertThat(derivatives(0.0, upWithoutOi)).isEqualByComparingTo("50");
        assertThat(derivatives(0.0, down)).isEqualByComparingTo("25");
        assertThat(derivatives(-0.1, down)).isEqualByComparingTo("75");
    }

    @Test
    void BR13_missingDerivativesData_leavesDerivativesNull_andSpotNeverHasOne() {
        IndicatorSnapshot ind = indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0);

        assertThat(compute(inputs(MarketType.FUTURES, "100", ind, 0.0, null, null, null))
                        .derivatives())
                .isNull();
        assertThat(derivatives(0.0, new DerivativesInputs(null, BigDecimal.ONE, BigDecimal.ONE)))
                .isNull();
        assertThat(derivatives(0.0, new DerivativesInputs(BigDecimal.ZERO, null, BigDecimal.ONE)))
                .isNull();
        assertThat(derivatives(0.0, new DerivativesInputs(BigDecimal.ZERO, BigDecimal.ONE, null)))
                .isNull();
        assertThat(compute(inputs(MarketType.SPOT, "100", ind, 0.0, null, null, derivatives()))
                        .derivatives())
                .isNull();
    }

    @Test
    void BR13_scores_carryTheSeriesTheFormulaVersionAndTheScoredSide() {
        ComponentScores scores = compute(inputs(
                MarketType.FUTURES,
                "100",
                indicators(100.0, 100.0, 100.0, 50.0, 0.0, 100.0),
                0.0,
                null,
                null,
                derivatives()));

        assertThat(scores.market()).isEqualTo(MarketType.FUTURES);
        assertThat(scores.timeframe()).isEqualTo("1h");
        assertThat(scores.formulaVersion()).isEqualTo("v2");
        assertThat(scores.scoredSide()).isEqualTo(ScoredSide.LONG);
    }

    static ComponentScores compute(ComponentInputs in) {
        return SetupComponents.compute(in, SetupScoreTunings.DEFAULT);
    }

    private static BigDecimal trend(IndicatorSnapshot ind) {
        return compute(inputs(MarketType.SPOT, "110", ind, 0.5, null, null, null))
                .trend();
    }

    private static BigDecimal momentum(IndicatorSnapshot ind, Double previous) {
        return compute(inputs(MarketType.SPOT, "100", ind, previous, null, null, null))
                .momentum();
    }

    /** The derivatives of a Futures candle with flat EMAs, scored on the side {@code histogram}'s sign picks. */
    private static BigDecimal derivatives(double histogram, DerivativesInputs d) {
        IndicatorSnapshot ind = indicators(100.0, 100.0, 100.0, 50.0, histogram, 100.0);
        return compute(inputs(MarketType.FUTURES, "100", ind, 0.0, null, null, d))
                .derivatives();
    }

    private static DerivativesInputs derivatives() {
        return new DerivativesInputs(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE);
    }

    static ComponentInputs inputs(
            MarketType market,
            String close,
            IndicatorSnapshot ind,
            Double previousHistogram,
            String support,
            String resistance,
            DerivativesInputs derivatives) {
        return inputs(market, close, ind, previousHistogram, support, resistance, derivatives, close, "100");
    }

    static ComponentInputs inputs(
            MarketType market,
            String close,
            IndicatorSnapshot ind,
            Double previousHistogram,
            String support,
            String resistance,
            DerivativesInputs derivatives,
            String open,
            String volume) {
        return new ComponentInputs(
                market,
                "1h",
                open == null ? null : new BigDecimal(open),
                new BigDecimal(close),
                volume == null ? VOLUME : new BigDecimal(volume),
                ind,
                previousHistogram,
                support == null ? null : new BigDecimal(support),
                resistance == null ? null : new BigDecimal(resistance),
                derivatives);
    }

    static IndicatorSnapshot indicators(
            Double ema20, Double ema50, Double ema200, Double rsi, Double histogram, Double volumeSma20) {
        return indicators(ema20, ema50, ema200, rsi, histogram, volumeSma20, null);
    }

    /** With {@code sigma} given, Bollinger bands around 100 that are 2σ wide on each side. */
    static IndicatorSnapshot indicators(
            Double ema20, Double ema50, Double ema200, Double rsi, Double histogram, Double volumeSma20, Double sigma) {
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
                sigma == null ? null : 100 + 2 * sigma,
                sigma == null ? null : 100.0,
                sigma == null ? null : 100 - 2 * sigma,
                volumeSma20);
    }
}
