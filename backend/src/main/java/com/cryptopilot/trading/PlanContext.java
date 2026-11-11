package com.cryptopilot.trading;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.MarginMode;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.model.enums.WarningSeverity;
import com.cryptopilot.trading.model.enums.WarningType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A trading plan as the AI Assistant may explain it (T-074): its terms, its risk panel as ratios and its warnings. The
 * capital, the money amounts sized from it and the Trader's free-text note are left out on purpose, so nothing that
 * describes the person leaves the system with the question (BR-51).
 *
 * <p>Rule: BR-23 to BR-27, BR-29, BR-51; TECHNICAL_DESIGN 7.14.
 *
 * @param pairId the pair
 * @param market the market
 * @param direction LONG or SHORT
 * @param entryType MARKET, LIMIT or STOP
 * @param entryPrice the planned entry price
 * @param stopLoss the stop loss
 * @param takeProfit the take profit
 * @param riskPercent the share of the capital risked, in percent
 * @param leverage the leverage on Futures, {@code null} on Spot
 * @param marginMode the margin mode on Futures, {@code null} on Spot
 * @param analysisTimeframe the timeframe the plan was made on
 * @param status the plan's status
 * @param setupScoreAtPlan the setup score when the plan was saved, or {@code null}
 * @param setupScorePreset the preset of that score, e.g. {@code DAY_TRADING v1}, or {@code null}
 * @param riskRewardRatio reward / risk
 * @param netRiskReward the risk/reward after fees, or {@code null} on a plan stored before fees were estimated
 * @param feeShareOfRisk the fees and slippage as a share of the risk, or {@code null}
 * @param estimatedLiquidationPrice the estimated liquidation price on Futures, or {@code null}
 * @param warnings the risk warnings of the plan
 */
public record PlanContext(
        UUID pairId,
        MarketType market,
        Direction direction,
        EntryType entryType,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal riskPercent,
        Integer leverage,
        MarginMode marginMode,
        String analysisTimeframe,
        PlanStatus status,
        BigDecimal setupScoreAtPlan,
        String setupScorePreset,
        BigDecimal riskRewardRatio,
        BigDecimal netRiskReward,
        BigDecimal feeShareOfRisk,
        BigDecimal estimatedLiquidationPrice,
        List<Warning> warnings) {

    public PlanContext {
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(market, "market");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** One risk warning (BR-29). */
    public record Warning(WarningType type, WarningSeverity severity, String message) {}
}
