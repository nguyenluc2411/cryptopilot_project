package com.cryptopilot.trading.dto.response;

import java.math.BigDecimal;

/**
 * The risk panel of a plan (BR-23 to BR-27), as stored at the last save or activation (BR-31). The Futures-only values
 * are {@code null} on Spot.
 *
 * <p>Rule: BR-23 to BR-27, BR-31.
 *
 * @param positionQuantity the sized quantity
 * @param notionalValue quantity × entry price
 * @param initialMargin notional / leverage; Futures only
 * @param maintenanceMarginRateUsed the bracket rate used; Futures only
 * @param riskAmount the loss at the stop loss
 * @param rewardAmount the gain at the take profit
 * @param riskRewardRatio reward / risk
 * @param estimatedLiquidationPrice the estimated liquidation price; Futures only
 */
public record PlanSnapshotResponse(
        BigDecimal positionQuantity,
        BigDecimal notionalValue,
        BigDecimal initialMargin,
        BigDecimal maintenanceMarginRateUsed,
        BigDecimal riskAmount,
        BigDecimal rewardAmount,
        BigDecimal riskRewardRatio,
        BigDecimal estimatedLiquidationPrice) {}
