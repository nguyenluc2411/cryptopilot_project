package com.cryptopilot.trading.service.impl;

import com.cryptopilot.trading.calculator.warning.WarningEvaluator;
import com.cryptopilot.trading.dto.response.PlanCalculationResponse;
import com.cryptopilot.trading.dto.response.PlanSnapshotResponse;
import com.cryptopilot.trading.dto.response.PlanWarningResponse;
import com.cryptopilot.trading.dto.response.StatusChangeResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.dto.response.TradingPlanSummaryResponse;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.model.CalculatedPlan;
import com.cryptopilot.trading.model.PlanCosts;
import com.cryptopilot.trading.model.PlanSnapshot;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.RiskInput;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.PlanStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Maps plans and calculations to the API's responses; nothing is decided here. */
final class PlanResponses {

    private PlanResponses() {}

    /** A plan in full; reads its warnings. */
    static TradingPlanResponse detail(TradingPlan plan) {
        List<PlanWarning> warnings = plan.getWarnings();
        return new TradingPlanResponse(
                plan.getId(),
                plan.getPairId(),
                plan.getMarket(),
                plan.getDirection(),
                plan.getEntryType(),
                entryPrice(plan),
                expectedFillPrice(plan),
                plan.getPlannedEntryPrice(),
                plan.getStopLoss(),
                plan.getTakeProfit(),
                plan.getCapital(),
                plan.getRiskPercent(),
                plan.getLeverage(),
                plan.getMarginMode(),
                plan.getExpiresAt(),
                plan.getNote(),
                plan.getInvalidationPrice(),
                plan.getMaxEntryDeviationR(),
                plan.getAnalysisTimeframe().code(),
                plan.getStatus(),
                plan.getInvalidationReason(),
                plan.getSetupScoreAtPlan(),
                plan.getSetupScorePreset(),
                plan.isEditable(),
                snapshot(plan.getSnapshot()),
                warnings(warnings),
                WarningEvaluator.blocksActivation(warnings),
                history(plan));
    }

    /** A row of the list; the warnings are not read. */
    static TradingPlanSummaryResponse summary(TradingPlan plan) {
        PlanSnapshot snapshot = plan.getSnapshot();
        return new TradingPlanSummaryResponse(
                plan.getId(),
                plan.getPairId(),
                plan.getMarket(),
                plan.getDirection(),
                plan.getEntryType(),
                entryPrice(plan),
                expectedFillPrice(plan),
                plan.getStopLoss(),
                plan.getTakeProfit(),
                plan.getLeverage(),
                snapshot.riskAmount(),
                snapshot.riskRewardRatio(),
                plan.getStatus(),
                plan.getCreatedAt(),
                plan.getActivatedAt(),
                plan.getExpiresAt(),
                plan.getInvalidatedAt(),
                plan.getInvalidationReason());
    }

    /**
     * The entry price a response answers, the same in the panel, the detail and the list: for MARKET the reference
     * price, which is the stored planned entry; a MARKET plan filled before the planned entry was stored (V32) has
     * only its fill.
     */
    private static BigDecimal entryPrice(TradingPlan plan) {
        return plan.getEntryType() == EntryType.MARKET && plan.getPlannedEntryPrice() != null
                ? plan.getPlannedEntryPrice()
                : plan.getEntryPrice();
    }

    /** MARKET only: the stored entry, the price the quantity was sized on (D-112 R6). */
    private static BigDecimal expectedFillPrice(TradingPlan plan) {
        return plan.getEntryType() == EntryType.MARKET ? plan.getEntryPrice() : null;
    }

    /**
     * The live risk panel. A MARKET entry answers its reference price and, beside it, the expected fill the plan is
     * sized on (D-112 R6).
     */
    static PlanCalculationResponse calculation(CalculatedPlan calculated) {
        RiskInput input = calculated.calculation().plan();
        boolean market = calculated.entry().entryType() == EntryType.MARKET;
        return new PlanCalculationResponse(
                market ? calculated.entry().reference() : input.entryPrice(),
                market ? input.entryPrice() : null,
                input.capital(),
                input.riskPercent(),
                snapshot(PlanSnapshot.of(calculated.calculation())),
                warnings(calculated.warnings()),
                WarningEvaluator.blocksActivation(calculated.warnings()));
    }

    private static PlanSnapshotResponse snapshot(PlanSnapshot snapshot) {
        PlanCosts costs = snapshot.costs();
        return new PlanSnapshotResponse(
                snapshot.positionQuantity(),
                snapshot.notionalValue(),
                snapshot.initialMargin(),
                snapshot.maintenanceMarginRateUsed(),
                snapshot.riskAmount(),
                snapshot.rewardAmount(),
                snapshot.riskRewardRatio(),
                snapshot.estimatedLiquidationPrice(),
                costs == null ? null : costs.estimatedEntryFee(),
                costs == null ? null : costs.estimatedStopLossExitFee(),
                costs == null ? null : costs.estimatedTakeProfitExitFee(),
                costs == null ? null : costs.estimatedSlippage(),
                costs == null ? null : costs.lossAtStopLoss(),
                costs == null ? null : costs.feeShareOfRisk(),
                costs == null ? null : costs.netRiskReward());
    }

    private static List<PlanWarningResponse> warnings(List<PlanWarning> warnings) {
        return warnings.stream()
                .map(warning -> new PlanWarningResponse(warning.type(), warning.severity(), warning.message()))
                .toList();
    }

    /** The statuses reached, oldest first; the lifecycle is linear, so the instants are the history (D-67). */
    private static List<StatusChangeResponse> history(TradingPlan plan) {
        List<StatusChangeResponse> history = new ArrayList<>();
        add(history, PlanStatus.DRAFT, plan.getCreatedAt());
        add(history, PlanStatus.ACTIVE, plan.getActivatedAt());
        add(history, PlanStatus.EXECUTED, plan.getExecutedAt());
        add(history, PlanStatus.CANCELLED, plan.getCancelledAt());
        add(history, PlanStatus.EXPIRED, plan.getExpiredAt());
        add(history, PlanStatus.INVALIDATED, plan.getInvalidatedAt());
        return history;
    }

    private static void add(List<StatusChangeResponse> history, PlanStatus status, Instant at) {
        if (at != null) {
            history.add(new StatusChangeResponse(status, at));
        }
    }
}
