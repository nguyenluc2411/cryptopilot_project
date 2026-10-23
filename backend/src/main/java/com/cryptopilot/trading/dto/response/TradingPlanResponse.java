package com.cryptopilot.trading.dto.response;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.InvalidationReason;
import com.cryptopilot.trading.model.enums.MarginMode;
import com.cryptopilot.trading.model.enums.PlanStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A plan in full (SCR-18): its values, the stored snapshot, its warnings and its status history.
 *
 * <p>Rule: BR-31, BR-32, BR-68, BR-69, BR-70; SRS 3.5.2; D-111, D-112.
 *
 * @param id the plan
 * @param pairId the pair
 * @param market SPOT or FUTURES
 * @param direction LONG or SHORT
 * @param entryType LIMIT, MARKET or STOP
 * @param entryPrice the entry price the snapshot was calculated with; the stop price of a STOP
 * @param plannedEntryPrice the planned entry the deviation is measured from: the entered price for LIMIT and STOP,
 *     the reference price of the last save for MARKET (BR-69)
 * @param stopLoss the stop loss
 * @param takeProfit the take profit
 * @param capital the capital
 * @param riskPercent the risk %
 * @param leverage the leverage; {@code null} on Spot
 * @param marginMode ISOLATED on Futures; {@code null} on Spot
 * @param expiresAt when an unfilled ACTIVE plan expires, or {@code null}
 * @param note the Trader's note
 * @param invalidationPrice the price that invalidates the waiting plan, or {@code null} (BR-68)
 * @param maxEntryDeviationR the plan's own entry deviation limit in R, or {@code null} (BR-69)
 * @param analysisTimeframe the timeframe the plan was analysed on, e.g. {@code 1h} (BR-70)
 * @param status the status
 * @param invalidationReason why the plan became INVALIDATED, or {@code null}
 * @param setupScoreAtPlan the setup score of the latest closed candle of the analysis timeframe, taken at
 *     activation; {@code null} before activation and when none could be read (never blocks the activation)
 * @param setupScorePreset the preset name and version that score was read under, e.g. {@code DAY_TRADING v1};
 *     {@code null} before activation
 * @param editable whether the plan may still be edited (DRAFT)
 * @param snapshot the stored calculation
 * @param warnings the stored warnings, most severe first
 * @param blocksActivation whether a stored warning would refuse activation (MSG18)
 * @param statusHistory the statuses reached, oldest first
 */
public record TradingPlanResponse(
        UUID id,
        UUID pairId,
        MarketType market,
        Direction direction,
        EntryType entryType,
        BigDecimal entryPrice,
        BigDecimal plannedEntryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal capital,
        BigDecimal riskPercent,
        Integer leverage,
        MarginMode marginMode,
        Instant expiresAt,
        String note,
        BigDecimal invalidationPrice,
        BigDecimal maxEntryDeviationR,
        String analysisTimeframe,
        PlanStatus status,
        InvalidationReason invalidationReason,
        BigDecimal setupScoreAtPlan,
        String setupScorePreset,
        boolean editable,
        PlanSnapshotResponse snapshot,
        List<PlanWarningResponse> warnings,
        boolean blocksActivation,
        List<StatusChangeResponse> statusHistory) {}
