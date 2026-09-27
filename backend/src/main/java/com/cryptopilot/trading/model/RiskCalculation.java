package com.cryptopilot.trading.model;

import java.math.BigDecimal;

/**
 * A sized position at full precision (CR-03): rounding for display is the caller's. It holds what BR-31's snapshot
 * of the sizing needs and the comparisons with the risk profile of TECHNICAL_DESIGN 7.5; turning those into
 * warnings is the warning evaluator's, and margin, leverage bracket and liquidation price are added by their own
 * calculations.
 *
 * <p>Rule: BR-23, BR-24, BR-25, BR-29 (profile comparisons), BR-31; CR-03; ADR-008; D-53.
 *
 * @param riskBudget capital × risk % / 100, in USDT
 * @param quantity the risk budget over the stop distance, floored to the step size, above 0
 * @param notional quantity × entry price, at least the pair's minimum notional
 * @param riskAmount quantity × |entry − stop loss|, at most the risk budget
 * @param rewardAmount quantity × |take profit − entry|
 * @param riskRewardRatio reward amount / risk amount
 * @param exceedsCapital a Spot notional above the capital (BR-25, BLOCKING INSUFFICIENT_CAPITAL); always
 *     {@code false} on Futures, whose capital is compared with the margin instead
 * @param riskPercentAboveProfile the risk % above the profile's risk per trade
 * @param leverageAboveProfile the leverage above the profile's maximum
 * @param totalOpenRiskPercent (risk amount + the other open risk) / capital × 100
 * @param totalOpenRiskAboveProfile the total open risk above the profile's maximum
 */
public record RiskCalculation(
        BigDecimal riskBudget,
        BigDecimal quantity,
        BigDecimal notional,
        BigDecimal riskAmount,
        BigDecimal rewardAmount,
        BigDecimal riskRewardRatio,
        boolean exceedsCapital,
        boolean riskPercentAboveProfile,
        boolean leverageAboveProfile,
        BigDecimal totalOpenRiskPercent,
        boolean totalOpenRiskAboveProfile)
        implements RiskOutcome {}
