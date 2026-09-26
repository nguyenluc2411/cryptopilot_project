package com.cryptopilot.market.model;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.SetupStyle;
import java.util.List;
import java.util.Objects;

/**
 * One style preset: its version, the timeframe it reads and its component weights per market.
 *
 * <p>A preset whose weights do not add up to exactly 100 on a market, or that has a weight outside 1–40, is refused.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4; D-53.
 * <p>Reference: Weights per D-53 / ADR-013.
 *
 * @param style the preset
 * @param version the preset version, e.g. {@code v1}
 * @param timeframe the timeframe it reads, as BR-08 spells it
 * @param spot the Spot weights: four components, no derivatives
 * @param futures the Futures weights: all five components
 */
public record StylePreset(
        SetupStyle style, String version, String timeframe, ComponentWeights spot, ComponentWeights futures) {

    static final int TOTAL = 100;
    static final int MIN_WEIGHT = 1;
    static final int MAX_WEIGHT = 40;
    static final List<String> TIMEFRAMES = List.of("15m", "1h", "4h", "1d");

    public StylePreset {
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(spot, "spot");
        Objects.requireNonNull(futures, "futures");
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException(style + ": a preset has a version");
        }
        if (!TIMEFRAMES.contains(timeframe)) {
            throw new IllegalArgumentException(style + ": timeframe " + timeframe + " is not one of " + TIMEFRAMES);
        }
        if (spot.derivatives() != null) {
            throw new IllegalArgumentException(style + ": Spot has no derivatives component");
        }
        if (futures.derivatives() == null) {
            throw new IllegalArgumentException(style + ": Futures weighs the derivatives component");
        }
        check(style, MarketType.SPOT, spot);
        check(style, MarketType.FUTURES, futures);
    }

    /** The weights of {@code market}. */
    public ComponentWeights weights(MarketType market) {
        return market == MarketType.SPOT ? spot : futures;
    }

    private static void check(SetupStyle style, MarketType market, ComponentWeights weights) {
        List<Integer> present = weights.derivatives() == null
                ? List.of(weights.trend(), weights.momentum(), weights.volume(), weights.level())
                : List.of(
                        weights.trend(), weights.momentum(), weights.volume(), weights.level(), weights.derivatives());
        for (int weight : present) {
            if (weight < MIN_WEIGHT || weight > MAX_WEIGHT) {
                throw new IllegalArgumentException(
                        style + " " + market + ": weight " + weight + " is outside " + MIN_WEIGHT + "–" + MAX_WEIGHT);
            }
        }
        if (weights.total() != TOTAL) {
            throw new IllegalArgumentException(
                    style + " " + market + ": weights add up to " + weights.total() + ", not " + TOTAL);
        }
    }
}
