package com.cryptopilot.market.calculator;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.ComponentWeights;
import com.cryptopilot.market.model.DominantSide;
import com.cryptopilot.market.model.SetupScore;
import com.cryptopilot.market.model.StylePreset;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Reads a setup score from stored component scores under one style preset: the weighted sum, rounded to an integer.
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
     */
    public static SetupScore score(StylePreset preset, ComponentScores components, DominantSide dominantSide) {
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(components, "components");
        if (!preset.timeframe().equals(components.timeframe())) {
            throw new IllegalArgumentException(
                    preset.style() + " reads " + preset.timeframe() + ", not " + components.timeframe());
        }
        if (components.market() == MarketType.SPOT && dominantSide != null) {
            throw new IllegalArgumentException("Spot has no dominant side");
        }
        Integer score = weighted(preset.weights(components.market()), components);
        return new SetupScore(score, components, preset.style(), preset.version(), preset.timeframe(), dominantSide);
    }

    private static Integer weighted(ComponentWeights weights, ComponentScores c) {
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
        return sum.divide(HUNDRED, 0, RoundingMode.HALF_UP).intValueExact();
    }

    private static BigDecimal part(int weight, BigDecimal component) {
        return component.multiply(BigDecimal.valueOf(weight));
    }
}
