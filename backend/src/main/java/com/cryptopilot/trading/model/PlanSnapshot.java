package com.cryptopilot.trading.model;

import com.cryptopilot.common.util.Rounding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The calculation results a plan stores (BR-31), at the scale of the columns that hold them: the quantity and the
 * liquidation price are tick or step multiples kept at 12 decimals, the USDT amounts and the rates and ratios are
 * brought to 8 decimals half-even (TECHNICAL_DESIGN 5.4). A snapshot is taken when the plan is saved and again at
 * activation; after that it never changes, whatever happens to brackets, fees or settings. The cost figures (D-116)
 * are {@code null} on a plan stored before they existed.
 *
 * <p>Rule: BR-23 to BR-27, BR-29, BR-31; TECHNICAL_DESIGN 5.4; D-116.
 *
 * @param positionQuantity the sized quantity
 * @param notionalValue quantity × entry price
 * @param initialMargin notional / leverage on Futures; {@code null} on Spot
 * @param maintenanceMarginRateUsed the rate of the leverage bracket used; {@code null} on Spot
 * @param riskAmount the loss at the stop loss
 * @param rewardAmount the gain at the take profit
 * @param riskRewardRatio reward amount / risk amount
 * @param estimatedLiquidationPrice the estimated liquidation price; {@code null} on Spot
 * @param costs the expected fees and slippage, see {@link PlanCosts}; {@code null} on a plan stored before D-116
 */
public record PlanSnapshot(
        BigDecimal positionQuantity,
        BigDecimal notionalValue,
        BigDecimal initialMargin,
        BigDecimal maintenanceMarginRateUsed,
        BigDecimal riskAmount,
        BigDecimal rewardAmount,
        BigDecimal riskRewardRatio,
        BigDecimal estimatedLiquidationPrice,
        PlanCosts costs) {

    /** Decimals of {@code numeric(28,12)}: the quantity and the prices. */
    private static final int PRICE_SCALE = 12;

    /** Decimals of the rate and ratio columns: the bracket rate, the risk/reward ratios and the fee share. */
    private static final int RATE_SCALE = 8;

    public PlanSnapshot {
        Objects.requireNonNull(positionQuantity, "positionQuantity");
        Objects.requireNonNull(notionalValue, "notionalValue");
        Objects.requireNonNull(riskAmount, "riskAmount");
        Objects.requireNonNull(rewardAmount, "rewardAmount");
        Objects.requireNonNull(riskRewardRatio, "riskRewardRatio");
    }

    /** The snapshot of a calculated plan, at the scale it is stored with. */
    public static PlanSnapshot of(PlanCalculation calculation) {
        RiskCalculation sized = calculation.sized();
        LiquidationEstimate liquidation = calculation.liquidation();
        return new PlanSnapshot(
                sized.quantity().setScale(PRICE_SCALE, RoundingMode.UNNECESSARY),
                Rounding.toAmount(sized.notional()),
                liquidation == null ? null : Rounding.toAmount(liquidation.initialMargin()),
                liquidation == null ? null : rate(liquidation.bracket().maintenanceMarginRate()),
                Rounding.toAmount(sized.riskAmount()),
                Rounding.toAmount(sized.rewardAmount()),
                rate(sized.riskRewardRatio()),
                liquidation == null
                        ? null
                        : liquidation.liquidationPrice().setScale(PRICE_SCALE, RoundingMode.UNNECESSARY),
                stored(calculation.costs()));
    }

    /** The costs at the scale of their columns: amounts and ratios at 8 decimals half-even. */
    private static PlanCosts stored(PlanCosts costs) {
        return new PlanCosts(
                Rounding.toAmount(costs.estimatedEntryFee()),
                Rounding.toAmount(costs.estimatedStopLossExitFee()),
                Rounding.toAmount(costs.estimatedTakeProfitExitFee()),
                Rounding.toAmount(costs.estimatedSlippage()),
                Rounding.toAmount(costs.lossAtStopLoss()),
                rate(costs.feeShareOfRisk()),
                rate(costs.netRiskReward()));
    }

    private static BigDecimal rate(BigDecimal value) {
        return value.setScale(RATE_SCALE, RoundingMode.HALF_EVEN);
    }
}
