package com.cryptopilot.trading.dto.response;

import java.math.BigDecimal;

/**
 * The risk panel of a plan (BR-23 to BR-27), as stored at the last save or activation (BR-31). The Futures-only values
 * are {@code null} on Spot. The fee figures (D-116) estimate the trading fees and the slippage of the entry and of the
 * exit at the stop loss or the take profit; funding and the liquidation clearance fee are not included. They are
 * {@code null} on a plan stored before they existed.
 *
 * <p>Rule: BR-23 to BR-27, BR-29, BR-31, BR-36; D-116.
 *
 * @param positionQuantity the sized quantity
 * @param notionalValue quantity × entry price
 * @param initialMargin notional / leverage; Futures only
 * @param maintenanceMarginRateUsed the bracket rate used; Futures only
 * @param riskAmount the loss at the stop loss
 * @param rewardAmount the gain at the take profit
 * @param riskRewardRatio reward / risk
 * @param estimatedLiquidationPrice the estimated liquidation price; Futures only
 * @param estimatedEntryFee the entry notional × the fee rate of the entry's expected liquidity role (taker for MARKET,
 *     STOP and a LIMIT the last price already reaches, maker for a resting LIMIT)
 * @param estimatedStopLossExitFee quantity × stop loss × the taker rate
 * @param estimatedTakeProfitExitFee quantity × take profit × the maker rate
 * @param estimatedSlippage {@code SLIPPAGE_BPS} on the taker legs (the entry when it is taker, the stop loss exit), in
 *     quote currency
 * @param lossAtStopLoss the real loss at the stop loss: the risk amount plus the entry fee, the stop loss exit fee and
 *     the slippage of those legs (a MARKET entry's slippage is already in the risk amount, as it is sized on the
 *     price paid)
 * @param feeShareOfRisk (loss at stop loss − risk amount) / risk amount; HIGH_FEE_SHARE from
 *     {@code FEE_RISK_WARN_RATIO}
 * @param netRiskReward the risk/reward after fees: (reward amount − entry fee − take profit exit fee − entry
 *     slippage) / loss at stop loss
 */
public record PlanSnapshotResponse(
        BigDecimal positionQuantity,
        BigDecimal notionalValue,
        BigDecimal initialMargin,
        BigDecimal maintenanceMarginRateUsed,
        BigDecimal riskAmount,
        BigDecimal rewardAmount,
        BigDecimal riskRewardRatio,
        BigDecimal estimatedLiquidationPrice,
        BigDecimal estimatedEntryFee,
        BigDecimal estimatedStopLossExitFee,
        BigDecimal estimatedTakeProfitExitFee,
        BigDecimal estimatedSlippage,
        BigDecimal lossAtStopLoss,
        BigDecimal feeShareOfRisk,
        BigDecimal netRiskReward) {}
