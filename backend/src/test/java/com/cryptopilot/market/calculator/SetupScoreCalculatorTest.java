package com.cryptopilot.market.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.market.SetupScoreTunings;
import com.cryptopilot.market.model.ComponentInputs;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.ComponentWeights;
import com.cryptopilot.market.model.DerivativesInputs;
import com.cryptopilot.market.model.IndicatorSnapshot;
import com.cryptopilot.market.model.SetupScore;
import com.cryptopilot.market.model.SetupScoreTuning;
import com.cryptopilot.market.model.StylePreset;
import com.cryptopilot.market.model.enums.CapReason;
import com.cryptopilot.market.model.enums.DominantSide;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.SetupStyle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The setup score read from stored components under a style preset, with the D-53 weights and the v2
 * no-room cap.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4; D-53.
 */
class SetupScoreCalculatorTest {

    private static final SetupScoreTuning TUNING = SetupScoreTunings.DEFAULT;

    static final StylePreset SCALPING = new StylePreset(
            SetupStyle.SCALPING,
            "v2",
            "15m",
            new ComponentWeights(20, 40, 25, 15, null),
            new ComponentWeights(15, 35, 20, 10, 20));
    static final StylePreset DAY_TRADING = new StylePreset(
            SetupStyle.DAY_TRADING,
            "v2",
            "1h",
            new ComponentWeights(35, 30, 15, 20, null),
            new ComponentWeights(30, 25, 15, 15, 15));
    static final StylePreset SWING = new StylePreset(
            SetupStyle.SWING,
            "v2",
            "4h",
            new ComponentWeights(40, 20, 10, 30, null),
            new ComponentWeights(35, 15, 10, 25, 15));

    @Test
    void BR13_spotScore_isTheWeightedSumOfItsFourComponents() {
        SetupScore score = SetupScoreCalculator.score(DAY_TRADING, spot("1h", "100", "50", "0", "50"), null, TUNING);

        assertThat(score.score()).isEqualTo(60);
    }

    @Test
    void BR13_futuresScore_weighsTheDerivativesComponentToo() {
        SetupScore score = SetupScoreCalculator.score(
                DAY_TRADING, futures("1h", "100", "100", "100", "100", "0"), DominantSide.LONG, TUNING);

        assertThat(score.score()).isEqualTo(85);
    }

    @Test
    void BR13_score_isRoundedHalfUpToAnInteger() {
        assertThat(SetupScoreCalculator.score(SWING, spot("4h", "0", "0", "5", "0"), null, TUNING)
                        .score())
                .isEqualTo(1);
        assertThat(SetupScoreCalculator.score(SWING, spot("4h", "0", "0", "4.99", "0"), null, TUNING)
                        .score())
                .isEqualTo(0);
    }

    @Test
    void BR13_score_spansZeroToHundred() {
        assertThat(SetupScoreCalculator.score(SCALPING, spot("15m", "0", "0", "0", "0"), null, TUNING)
                        .score())
                .isEqualTo(0);
        assertThat(SetupScoreCalculator.score(
                                SCALPING,
                                futures("15m", "100", "100", "100", "100", "100"),
                                DominantSide.NEUTRAL,
                                TUNING)
                        .score())
                .isEqualTo(100);
    }

    @Test
    void BR13_everyScore_carriesPresetNameVersionAndTimeframe() {
        SetupScore score = SetupScoreCalculator.score(SWING, spot("4h", "10", "20", "30", "40"), null, TUNING);

        assertThat(score.style()).isEqualTo(SetupStyle.SWING);
        assertThat(score.presetVersion()).isEqualTo("v2");
        assertThat(score.timeframe()).isEqualTo("4h");
        assertThat(score.components().formulaVersion()).isEqualTo("v2");
    }

    @Test
    void BR13_futuresScore_carriesTheDominantSideBesideIt_andItDoesNotChangeTheScore() {
        ComponentScores components = futures("1h", "100", "50", "50", "50", "50");

        SetupScore asLong = SetupScoreCalculator.score(DAY_TRADING, components, DominantSide.LONG, TUNING);
        SetupScore asShort = SetupScoreCalculator.score(DAY_TRADING, components, DominantSide.SHORT, TUNING);

        assertThat(asLong.dominantSide()).isEqualTo(DominantSide.LONG);
        assertThat(asShort.dominantSide()).isEqualTo(DominantSide.SHORT);
        assertThat(asLong.score()).isEqualTo(asShort.score());
    }

    @Test
    void BR13_aMissingComponent_leavesTheScoreNull() {
        assertThat(SetupScoreCalculator.score(DAY_TRADING, spot("1h", null, "50", "50", "50"), null, TUNING)
                        .score())
                .isNull();
        assertThat(SetupScoreCalculator.score(DAY_TRADING, spot("1h", "50", null, "50", "50"), null, TUNING)
                        .score())
                .isNull();
        assertThat(SetupScoreCalculator.score(DAY_TRADING, spot("1h", "50", "50", null, "50"), null, TUNING)
                        .score())
                .isNull();
        assertThat(SetupScoreCalculator.score(DAY_TRADING, spot("1h", "50", "50", "50", null), null, TUNING)
                        .score())
                .isNull();
        assertThat(SetupScoreCalculator.score(DAY_TRADING, futures("1h", "50", "50", "50", "50", null), null, TUNING)
                        .score())
                .isNull();
    }

    @Test
    void BR13_componentsOfAnotherTimeframe_areRefused() {
        assertThatThrownBy(() -> SetupScoreCalculator.score(SWING, spot("1h", "0", "0", "0", "0"), null, TUNING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void BR13_spotWithADominantSide_isRefused() {
        assertThatThrownBy(() -> SetupScoreCalculator.score(
                        DAY_TRADING, spot("1h", "0", "0", "0", "0"), DominantSide.LONG, TUNING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The worked example of TECHNICAL_DESIGN 7.4 carried to a score. */
    @Test
    void BR13_workedExample_scoresLowOnSpotAndHighOnFutures() {
        IndicatorSnapshot down = SetupComponentsTest.indicators(95.0, 100.0, 110.0, 35.0, -0.8, 100.0);
        BigDecimal close = new BigDecimal("90");
        DerivativesInputs derivatives = new DerivativesInputs(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE);
        ComponentInputs spotIn = new ComponentInputs(
                MarketType.SPOT,
                "1h",
                close,
                close,
                new BigDecimal("100"),
                down,
                -0.5,
                new BigDecimal("70"),
                new BigDecimal("92"),
                null);
        ComponentInputs futuresIn = new ComponentInputs(
                MarketType.FUTURES,
                "1h",
                close,
                close,
                new BigDecimal("100"),
                down,
                -0.5,
                new BigDecimal("70"),
                new BigDecimal("92"),
                derivatives);

        SetupScore spot =
                SetupScoreCalculator.score(DAY_TRADING, SetupComponents.compute(spotIn, TUNING), null, TUNING);
        SetupScore futures = SetupScoreCalculator.score(
                DAY_TRADING,
                SetupComponents.compute(futuresIn, TUNING),
                SetupComponents.dominantSide(MarketType.FUTURES, close, down),
                TUNING);

        // v2: volume 33.33 (saturates at 2x); 35·0 + 30·0 + 15·33.33 + 20·3.33 = 566.55 → 6
        assertThat(spot.score()).isEqualTo(6);
        // v2: SHORT gets 20 of funding and 0 of open interest, as the price rose against it;
        // 30·100 + 25·100 + 15·33.33 + 15·100 + 15·20 = 7799.95 → 78
        assertThat(futures.score()).isEqualTo(78);
        assertThat(futures.dominantSide()).isEqualTo(DominantSide.SHORT);
    }

    @Test
    void BR13_futuresUpTrendAtResistance_noLongerScoresHigh() {
        IndicatorSnapshot up = SetupComponentsTest.indicators(105.0, 100.0, 90.0, 60.0, 1.0, 100.0);
        ComponentScores components = SetupComponentsTest.compute(SetupComponentsTest.inputs(
                MarketType.FUTURES,
                "110",
                up,
                0.5,
                "100",
                "111",
                new DerivativesInputs(new BigDecimal("0.0001"), BigDecimal.ONE, BigDecimal.ONE),
                "108",
                "200"));

        SetupScore score = SetupScoreCalculator.score(DAY_TRADING, components, DominantSide.LONG, TUNING);

        // 30·100 + 25·100 + 15·100 + 15·3.33 + 15·75 = 8175 → capped at 54 by level 3.33
        assertThat(score.score()).isEqualTo(54);
        assertThat(score.capReason()).isEqualTo(CapReason.LOW_ROOM);
    }

    /** DAY_TRADING Spot chasing a strong up-trend: RSI 78, close 0.5 under resistance and 10 over support. */
    @Test
    void BR13_chasingAtResistance_isCappedBelowTheLowestAlertThreshold() {
        IndicatorSnapshot up = SetupComponentsTest.indicators(105.0, 100.0, 90.0, 78.0, 1.0, 100.0);
        ComponentScores components = SetupComponentsTest.compute(
                SetupComponentsTest.inputs(MarketType.SPOT, "110", up, 0.5, "100", "110.5", null, "108", "200"));

        SetupScore score = SetupScoreCalculator.score(DAY_TRADING, components, null, TUNING);

        assertThat(score.score()).isLessThanOrEqualTo(54);
        assertThat(score.capReason()).isEqualTo(CapReason.LOW_ROOM);
    }

    @Test
    void BR13_rsiAcrossSeventy_movesTheScoreByAtMostOne() {
        assertThat(Math.abs(spotScoreAtRsi(70.0) - spotScoreAtRsi(70.2))).isLessThanOrEqualTo(1);
    }

    @Test
    void BR13_theRoomCap_isContinuous() {
        int below = SetupScoreCalculator.score(DAY_TRADING, spot("1h", "100", "100", "100", "19.9"), null, TUNING)
                .score();
        int above = SetupScoreCalculator.score(DAY_TRADING, spot("1h", "100", "100", "100", "20.1"), null, TUNING)
                .score();

        assertThat(Math.abs(below - above)).isLessThanOrEqualTo(1);
    }

    @Test
    void BR13_theRoomCap_risesFromItsScoreToNoCap() {
        assertThat(SetupScoreCalculator.roomCap(new BigDecimal("0"), TUNING)).isEqualByComparingTo("54");
        assertThat(SetupScoreCalculator.roomCap(new BigDecimal("10"), TUNING)).isEqualByComparingTo("54");
        assertThat(SetupScoreCalculator.roomCap(new BigDecimal("20"), TUNING)).isEqualByComparingTo("77");
        assertThat(SetupScoreCalculator.roomCap(new BigDecimal("30"), TUNING)).isEqualByComparingTo("100");
        assertThat(SetupScoreCalculator.roomCap(new BigDecimal("80"), TUNING)).isEqualByComparingTo("100");
    }

    @Test
    void BR13_aCapThatDoesNotLowerTheScore_givesNoReason() {
        SetupScore low = SetupScoreCalculator.score(DAY_TRADING, spot("1h", "0", "50", "50", "0"), null, TUNING);
        SetupScore roomy = SetupScoreCalculator.score(DAY_TRADING, spot("1h", "100", "100", "100", "60"), null, TUNING);

        assertThat(low.score()).isEqualTo(23);
        assertThat(low.capReason()).isNull();
        assertThat(roomy.score()).isEqualTo(92);
        assertThat(roomy.capReason()).isNull();
    }

    @Test
    void BR13_v1Components_areWeightedWithoutTheCap() {
        ComponentScores v1 =
                new ComponentScores(MarketType.SPOT, "1h", "v1", dec("100"), dec("100"), dec("100"), dec("0"), null);

        SetupScore score = SetupScoreCalculator.score(DAY_TRADING, v1, null, TUNING);

        assertThat(score.score()).isEqualTo(80);
        assertThat(score.capReason()).isNull();
    }

    private static int spotScoreAtRsi(double rsi) {
        IndicatorSnapshot up = SetupComponentsTest.indicators(105.0, 100.0, 90.0, rsi, 1.0, 100.0);
        ComponentScores components = SetupComponentsTest.compute(
                SetupComponentsTest.inputs(MarketType.SPOT, "110", up, 0.5, "100", "130", null, "108", "150"));
        return SetupScoreCalculator.score(DAY_TRADING, components, null, TUNING).score();
    }

    /**
     * D-53 rule 1: the risk profile is never an input. Nothing of the user's profile or of risk reaches a public
     * method of the two calculators, so no profile can change a score. The package rule is in the architecture tests.
     */
    @Test
    void D53_noScoringMethod_takesAUserOrRiskParameter() {
        Stream<Method> methods = Stream.of(SetupComponents.class, SetupScoreCalculator.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> Modifier.isPublic(method.getModifiers()));

        assertThat(methods.flatMap(method -> Arrays.stream(method.getParameterTypes())))
                .allSatisfy(type -> {
                    assertThat(type.getName()).doesNotContain(".user.", ".auth.", ".trading.");
                    assertThat(type.getSimpleName()).doesNotContain("Risk", "TradingStyle", "Profile", "User");
                });
    }

    private static ComponentScores spot(String timeframe, String t, String mo, String v, String l) {
        return new ComponentScores(MarketType.SPOT, timeframe, "v2", dec(t), dec(mo), dec(v), dec(l), null);
    }

    private static ComponentScores futures(String timeframe, String t, String mo, String v, String l, String d) {
        return new ComponentScores(MarketType.FUTURES, timeframe, "v2", dec(t), dec(mo), dec(v), dec(l), dec(d));
    }

    private static BigDecimal dec(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
