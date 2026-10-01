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
import com.cryptopilot.trading.model.PlanSnapshot;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.RiskInput;
import com.cryptopilot.trading.model.enums.PlanStatus;
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
                plan.getEntryPrice(),
                plan.getStopLoss(),
                plan.getTakeProfit(),
                plan.getCapital(),
                plan.getRiskPercent(),
                plan.getLeverage(),
                plan.getMarginMode(),
                plan.getExpiresAt(),
                plan.getNote(),
                plan.getStatus(),
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
                plan.getEntryPrice(),
                plan.getStopLoss(),
                plan.getTakeProfit(),
                plan.getLeverage(),
                snapshot.riskAmount(),
                snapshot.riskRewardRatio(),
                plan.getStatus(),
                plan.getCreatedAt(),
                plan.getActivatedAt(),
                plan.getExpiresAt());
    }

    /** The live risk panel. */
    static PlanCalculationResponse calculation(CalculatedPlan calculated) {
        RiskInput input = calculated.calculation().plan();
        return new PlanCalculationResponse(
                input.entryPrice(),
                input.capital(),
                input.riskPercent(),
                snapshot(PlanSnapshot.of(calculated.calculation())),
                warnings(calculated.warnings()),
                WarningEvaluator.blocksActivation(calculated.warnings()));
    }

    private static PlanSnapshotResponse snapshot(PlanSnapshot snapshot) {
        return new PlanSnapshotResponse(
                snapshot.positionQuantity(),
                snapshot.notionalValue(),
                snapshot.initialMargin(),
                snapshot.maintenanceMarginRateUsed(),
                snapshot.riskAmount(),
                snapshot.rewardAmount(),
                snapshot.riskRewardRatio(),
                snapshot.estimatedLiquidationPrice());
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
        return history;
    }

    private static void add(List<StatusChangeResponse> history, PlanStatus status, Instant at) {
        if (at != null) {
            history.add(new StatusChangeResponse(status, at));
        }
    }
}
