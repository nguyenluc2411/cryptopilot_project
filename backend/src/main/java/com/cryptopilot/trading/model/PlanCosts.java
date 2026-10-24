package com.cryptopilot.trading.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The fees and slippage a plan is expected to pay, at full precision (CR-03), and what they do to its risk and
 * reward. Funding and the liquidation clearance fee are not included. The sizing does not change: the quantity is
 * still the risk budget over |entry − stop loss| (BR-23).
 *
 * <p>Rule: BR-29, BR-31, BR-36; D-116.
 *
 * @param estimatedEntryFee the entry notional × the rate of the entry's expected liquidity role
 * @param estimatedStopLossExitFee quantity × stop loss × the taker rate
 * @param estimatedTakeProfitExitFee quantity × take profit × the maker rate
 * @param estimatedSlippage the slippage of the taker legs, in quote currency: the entry when it is taker, and the stop
 *     loss exit
 * @param lossAtStopLoss the risk amount with the entry fee, the stop loss exit fee and the slippage of those two legs
 *     not already in the risk amount
 * @param feeShareOfRisk (loss at stop loss − risk amount) / risk amount
 * @param netRiskReward the reward amount less the entry fee, the take profit exit fee and the entry slippage not already
 *     in the reward amount, over the loss at stop loss
 */
public record PlanCosts(
        BigDecimal estimatedEntryFee,
        BigDecimal estimatedStopLossExitFee,
        BigDecimal estimatedTakeProfitExitFee,
        BigDecimal estimatedSlippage,
        BigDecimal lossAtStopLoss,
        BigDecimal feeShareOfRisk,
        BigDecimal netRiskReward) {

    public PlanCosts {
        Objects.requireNonNull(estimatedEntryFee, "estimatedEntryFee");
        Objects.requireNonNull(estimatedStopLossExitFee, "estimatedStopLossExitFee");
        Objects.requireNonNull(estimatedTakeProfitExitFee, "estimatedTakeProfitExitFee");
        Objects.requireNonNull(estimatedSlippage, "estimatedSlippage");
        Objects.requireNonNull(lossAtStopLoss, "lossAtStopLoss");
        Objects.requireNonNull(feeShareOfRisk, "feeShareOfRisk");
        Objects.requireNonNull(netRiskReward, "netRiskReward");
    }
}
