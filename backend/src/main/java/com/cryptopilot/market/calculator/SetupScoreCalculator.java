package com.cryptopilot.market.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.ComponentWeights;
import com.cryptopilot.market.model.SetupScore;
import com.cryptopilot.market.model.SetupScoreTuning;
import com.cryptopilot.market.model.StylePreset;
import com.cryptopilot.market.model.enums.CapReason;
import com.cryptopilot.market.model.enums.DominantSide;
import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Reads a setup score from stored component scores under one style preset: the weighted sum, capped when the level
 * component leaves too little room on the scored side, rounded to an integer.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4; D-53.
 * <p>Reference: Weights per D-53 / ADR-013.
 */
public final class SetupScoreCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SetupScoreCalculator() {}

    /**
     * Weights {@code components} under {@code preset}; the score is {@code null} when a component it needs is missing.
     *
     * @param preset the style preset
     * @param components the component scores, read on the preset's timeframe
     * @param dominantSide the Futures trend side to return beside the score, or {@code null}; always {@code null} on Spot
     * @param tuning the no-room cap
     */
    public static SetupScore score(
            StylePreset preset, ComponentScores components, DominantSide dominantSide, SetupScoreTuning tuning) {
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(components, "components");
        Objects.requireNonNull(tuning, "tuning");
        if (!preset.timeframe().equals(components.timeframe())) {
            throw new IllegalArgumentException(
                    preset.style() + " reads " + preset.timeframe() + ", not " + components.timeframe());
        }
        if (components.market() == MarketType.SPOT && dominantSide != null) {
            throw new IllegalArgumentException("Spot has no dominant side");
        }
        BigDecimal sum = weighted(preset.weights(components.market()), components);
        if (sum == null) {
            return new SetupScore(
                    null, components, preset.style(), preset.version(), preset.timeframe(), dominantSide, null);
        }
        // v1 components carry no scored side, so their level is not the room on one side.
        BigDecimal cap = SetupComponents.FORMULA_VERSION.equals(components.formulaVersion())
                ? roomCap(components.level(), tuning)
                : HUNDRED;
        int uncapped = rounded(sum);
        int score = rounded(sum.min(cap));
        CapReason reason = score < uncapped ? CapReason.LOW_ROOM : null;
        return new SetupScore(
                score, components, preset.style(), preset.version(), preset.timeframe(), dominantSide, reason);
    }

    /**
     * The cap at a level component: {@code roomCapScore} up to {@code roomCapFrom}, rising linearly to 100 at
     * {@code roomCapTo}.
     *
     * <p>Rule: BR-13.
     * <p>Reference: Murphy, J. J. (1999). Technical Analysis of the Financial Markets, ch. 7 (room to the next level).
     */
    public static BigDecimal roomCap(BigDecimal level, SetupScoreTuning t) {
        if (level.compareTo(t.roomCapFrom()) <= 0) {
            return t.roomCapScore();
        }
        if (level.compareTo(t.roomCapTo()) >= 0) {
            return HUNDRED;
        }
        BigDecimal share =
                Rounding.divide(level.subtract(t.roomCapFrom()), t.roomCapTo().subtract(t.roomCapFrom()));
        return t.roomCapScore().add(HUNDRED.subtract(t.roomCapScore()).multiply(share));
    }

    private static BigDecimal weighted(ComponentWeights weights, ComponentScores c) {
        boolean futures = c.market() == MarketType.FUTURES;
        if (c.trend() == null
                || c.momentum() == null
                || c.volume() == null
                || c.level() == null
                || (futures && c.derivatives() == null)) {
            return null;
        }
        BigDecimal sum = part(weights.trend(), c.trend())
                .add(part(weights.momentum(), c.momentum()))
                .add(part(weights.volume(), c.volume()))
                .add(part(weights.level(), c.level()));
        if (futures) {
            sum = sum.add(part(weights.derivatives(), c.derivatives()));
        }
        return Rounding.divide(sum, HUNDRED);
    }

    private static int rounded(BigDecimal score) {
        return score.setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private static BigDecimal part(int weight, BigDecimal component) {
        return component.multiply(BigDecimal.valueOf(weight));
    }
}
