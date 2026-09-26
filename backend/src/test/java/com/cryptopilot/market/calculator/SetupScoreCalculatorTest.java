package com.cryptopilot.market.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.SetupStyle;
import com.cryptopilot.market.model.ComponentInputs;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.ComponentWeights;
import com.cryptopilot.market.model.DerivativesInputs;
import com.cryptopilot.market.model.DominantSide;
import com.cryptopilot.market.model.IndicatorSnapshot;
import com.cryptopilot.market.model.SetupScore;
import com.cryptopilot.market.model.StylePreset;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The setup score read from stored components under a style preset, with the D-53 v1 weights.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4; D-53.
 */
class SetupScoreCalculatorTest {

    static final StylePreset SCALPING = new StylePreset(
            SetupStyle.SCALPING,
            "v1",
            "15m",
            new ComponentWeights(20, 40, 25, 15, null),
            new ComponentWeights(15, 35, 20, 10, 20));
    static final StylePreset DAY_TRADING = new StylePreset(
            SetupStyle.DAY_TRADING,
            "v1",
            "1h",
            new ComponentWeights(35, 30, 15, 20, null),
            new ComponentWeights(30, 25, 15, 15, 15));
    static final StylePreset SWING = new StylePreset(
            SetupStyle.SWING,
            "v1",
            "4h",
            new ComponentWeights(40, 20, 10, 30, null),
            new ComponentWeights(35, 15, 10, 25, 15));

    @Test
    void BR13_spotScore_isTheWeightedSumOfItsFourComponents() {
        SetupScore score = SetupScoreCalculator.score(DAY_TRADING, spot("1h", "100", "50", "0", "50"), null);

        assertThat(score.score()).isEqualTo(60);
    }

    @Test
    void BR13_futuresScore_weighsTheDerivativesComponentToo() {
        SetupScore score = SetupScoreCalculator.score(
                DAY_TRADING, futures("1h", "100", "100", "100", "100", "0"), DominantSide.LONG);

        assertThat(score.score()).isEqualTo(85);
    }

    @Test
    void BR13_score_isRoundedHalfUpToAnInteger() {
        assertThat(SetupScoreCalculator.score(SWING, spot("4h", "0", "0", "5", "0"), null)
                        .score())
                .isEqualTo(1);
        assertThat(SetupScoreCalculator.score(SWING, spot("4h", "0", "0", "4.99", "0"), null)
                        .score())
                .isEqualTo(0);
    }

    @Test
    void BR13_score_spansZeroToHundred() {
        assertThat(SetupScoreCalculator.score(SCALPING, spot("15m", "0", "0", "0", "0"), null)
                        .score())
                .isEqualTo(0);
        assertThat(SetupScoreCalculator.score(
                                SCALPING, futures("15m", "100", "100", "100", "100", "100"), DominantSide.NEUTRAL)
                        .score())
                .isEqualTo(100);
    }

    @Test
    void BR13_everyScore_carriesPresetNameVersionAndTimeframe() {
        SetupScore score = SetupScoreCalculator.score(SWING, spot("4h", "10", "20", "30", "40"), null);

        assertThat(score.style()).isEqualTo(SetupStyle.SWING);
        assertThat(score.presetVersion()).isEqualTo("v1");
        assertThat(score.timeframe()).isEqualTo("4h");
        assertThat(score.components().formulaVersion()).isEqualTo("v1");
    }

    @Test
    void BR13_futuresScore_carriesTheDominantSideBesideIt_andItDoesNotChangeTheScore() {
        ComponentScores components = futures("1h", "100", "50", "50", "50", "50");

        SetupScore asLong = SetupScoreCalculator.score(DAY_TRADING, components, DominantSide.LONG);
        SetupScore asShort = SetupScoreCalculator.score(DAY_TRADING, components, DominantSide.SHORT);

        assertThat(asLong.dominantSide()).isEqualTo(DominantSide.LONG);
        assertThat(asShort.dominantSide()).isEqualTo(DominantSide.SHORT);
        assertThat(asLong.score()).isEqualTo(asShort.score());
    }

    @Test
    void BR13_aMissingComponent_leavesTheScoreNull() {
        assertThat(SetupScoreCalculator.score(DAY_TRADING, spot("1h", null, "50", "50", "50"), null)
                        .score())
                .isNull();
        assertThat(SetupScoreCalculator.score(DAY_TRADING, spot("1h", "50", null, "50", "50"), null)
                        .score())
                .isNull();
        assertThat(SetupScoreCalculator.score(DAY_TRADING, spot("1h", "50", "50", null, "50"), null)
                        .score())
                .isNull();
        assertThat(SetupScoreCalculator.score(DAY_TRADING, spot("1h", "50", "50", "50", null), null)
                        .score())
                .isNull();
        assertThat(SetupScoreCalculator.score(DAY_TRADING, futures("1h", "50", "50", "50", "50", null), null)
                        .score())
                .isNull();
    }

    @Test
    void BR13_componentsOfAnotherTimeframe_areRefused() {
        assertThatThrownBy(() -> SetupScoreCalculator.score(SWING, spot("1h", "0", "0", "0", "0"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void BR13_spotWithADominantSide_isRefused() {
        assertThatThrownBy(() ->
                        SetupScoreCalculator.score(DAY_TRADING, spot("1h", "0", "0", "0", "0"), DominantSide.LONG))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The worked example of TECHNICAL_DESIGN 7.4 carried to a score, with volume 50 and derivatives 75. */
    @Test
    void BR13_workedExample_scoresLowOnSpotAndHighOnFutures() {
        IndicatorSnapshot down = SetupComponentsTest.indicators(95.0, 100.0, 110.0, 35.0, -0.8, 100.0);
        BigDecimal close = new BigDecimal("90");
        DerivativesInputs derivatives = new DerivativesInputs(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE);
        ComponentInputs spotIn = new ComponentInputs(
                MarketType.SPOT,
                "1h",
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
                new BigDecimal("100"),
                down,
                -0.5,
                new BigDecimal("70"),
                new BigDecimal("92"),
                derivatives);

        SetupScore spot = SetupScoreCalculator.score(DAY_TRADING, SetupComponents.compute(spotIn), null);
        SetupScore futures = SetupScoreCalculator.score(
                DAY_TRADING,
                SetupComponents.compute(futuresIn),
                SetupComponents.dominantSide(MarketType.FUTURES, close, down));

        // 35·0 + 30·0 + 15·50 + 20·3.33 = 816.6 → 8
        assertThat(spot.score()).isEqualTo(8);
        // 30·100 + 25·100 + 15·50 + 15·100 + 15·75 = 8875 → 89
        assertThat(futures.score()).isEqualTo(89);
        assertThat(futures.dominantSide()).isEqualTo(DominantSide.SHORT);
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
        return new ComponentScores(MarketType.SPOT, timeframe, "v1", dec(t), dec(mo), dec(v), dec(l), null);
    }

    private static ComponentScores futures(String timeframe, String t, String mo, String v, String l, String d) {
        return new ComponentScores(MarketType.FUTURES, timeframe, "v1", dec(t), dec(mo), dec(v), dec(l), dec(d));
    }

    private static BigDecimal dec(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
