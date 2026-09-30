package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The BR-29 thresholds that the risk profile does not define. They are system settings, read by the caller and passed
 * in; the rules hold none of their values.
 *
 * <p>Rule: BR-29; TECHNICAL_DESIGN 7.5; A-34.
 *
 * @param minRiskRewardRatio LOW_RR below this ratio ({@code WARN_LOW_RR_RATIO})
 * @param wideStopLossPercent WIDE_STOP_LOSS above this % of the entry ({@code WARN_WIDE_STOP_LOSS_PERCENT})
 * @param highFundingRate HIGH_FUNDING_RATE at or above this absolute rate ({@code WARN_HIGH_FUNDING_RATE})
 */
public record WarningThresholds(
        BigDecimal minRiskRewardRatio, BigDecimal wideStopLossPercent, BigDecimal highFundingRate) {

    public WarningThresholds {
        requirePositive(minRiskRewardRatio, "minRiskRewardRatio");
        requirePositive(wideStopLossPercent, "wideStopLossPercent");
        requirePositive(highFundingRate, "highFundingRate");
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (Objects.requireNonNull(value, name + " must not be null").signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
    }
}
