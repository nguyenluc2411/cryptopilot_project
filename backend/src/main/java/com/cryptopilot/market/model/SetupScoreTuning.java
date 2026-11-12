package com.cryptopilot.market.model;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Objects;

/**
 * The tunable values of the v2 component formulas and of the no-room cap, bound from
 * {@code cryptopilot.market.setup-score.v2} and checked when built, so a wrong value stops the application at start-up.
 *
 * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4; D-53 (rule 6).
 *
 * @param fundingBaseline the funding rate both sides score evenly at, as a fraction
 * @param fundingSpan the funding distance from the baseline that moves the funding part across its whole range
 * @param trendBandSigma how many Bollinger σ a trend difference must exceed to hold
 * @param volumeSaturation the volume ratio at which the volume component reaches 100
 * @param againstSideFactor the factor on the volume component of a candle against the scored side
 * @param levelMinRiskSigma the smallest risk to the level behind, in Bollinger σ
 * @param roomCapScore the setup score cap at the lowest level component
 * @param roomCapFrom the level component at or below which the cap is {@code roomCapScore}
 * @param roomCapTo the level component at or above which there is no cap
 */
public record SetupScoreTuning(
        BigDecimal fundingBaseline,
        BigDecimal fundingSpan,
        BigDecimal trendBandSigma,
        BigDecimal volumeSaturation,
        BigDecimal againstSideFactor,
        BigDecimal levelMinRiskSigma,
        BigDecimal roomCapScore,
        BigDecimal roomCapFrom,
        BigDecimal roomCapTo) {

    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_FUNDING = new BigDecimal("0.01");

    public SetupScoreTuning {
        within("funding-baseline", fundingBaseline, MAX_FUNDING.negate(), MAX_FUNDING);
        within("funding-span", fundingSpan, BigDecimal.ZERO, MAX_FUNDING);
        positive("funding-span", fundingSpan);
        within("trend-band-sigma", trendBandSigma, BigDecimal.ZERO, BigDecimal.TWO);
        within("volume-saturation", volumeSaturation, HALF, BigDecimal.TEN);
        if (volumeSaturation.compareTo(HALF) == 0) {
            throw new IllegalArgumentException("setup-score.v2.volume-saturation must be above 0.5");
        }
        within("against-side-factor", againstSideFactor, BigDecimal.ZERO, BigDecimal.ONE);
        within("level-min-risk-sigma", levelMinRiskSigma, BigDecimal.ZERO, BigDecimal.valueOf(5));
        within("room-cap-score", roomCapScore, BigDecimal.ZERO, HUNDRED);
        within("room-cap-from", roomCapFrom, BigDecimal.ZERO, HUNDRED);
        within("room-cap-to", roomCapTo, BigDecimal.ZERO, HUNDRED);
        if (roomCapTo.compareTo(roomCapFrom) <= 0) {
            throw new IllegalArgumentException("setup-score.v2.room-cap-to must be above room-cap-from");
        }
    }

    /**
     * Refuses a cap that would still let a capped score reach an alert threshold.
     *
     * @param alertThresholds the minimum setup scores to alert of the risk profiles; none means nothing to check
     */
    public void requireCapBelow(Collection<Integer> alertThresholds) {
        alertThresholds.stream()
                .filter(Objects::nonNull)
                .min(Integer::compare)
                .filter(lowest -> roomCapScore.compareTo(BigDecimal.valueOf(lowest)) >= 0)
                .ifPresent(lowest -> {
                    throw new IllegalArgumentException("setup-score.v2.room-cap-score " + roomCapScore
                            + " must be below the lowest alert threshold " + lowest);
                });
    }

    private static void within(String name, BigDecimal value, BigDecimal min, BigDecimal max) {
        Objects.requireNonNull(value, "setup-score.v2." + name + " must be declared");
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(
                    "setup-score.v2." + name + " " + value + " is outside " + min + "–" + max);
        }
    }

    private static void positive(String name, BigDecimal value) {
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("setup-score.v2." + name + " must be above 0");
        }
    }
}
