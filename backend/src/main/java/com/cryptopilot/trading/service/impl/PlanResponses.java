package com.cryptopilot.trading.service.impl;

import com.cryptopilot.trading.PositionLevels;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.calculator.TradeResultCalculator;
import com.cryptopilot.trading.calculator.warning.WarningEvaluator;
import com.cryptopilot.trading.dto.response.PlanCalculationResponse;
import com.cryptopilot.trading.dto.response.PlanSnapshotResponse;
import com.cryptopilot.trading.dto.response.PlanWarningResponse;
import com.cryptopilot.trading.dto.response.PositionLevelChangeResponse;
import com.cryptopilot.trading.dto.response.PositionResponse;
import com.cryptopilot.trading.dto.response.StatusChangeResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.dto.response.TradingPlanSummaryResponse;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.model.Breakeven;
import com.cryptopilot.trading.model.CalculatedPlan;
import com.cryptopilot.trading.model.NetPnl;
import com.cryptopilot.trading.model.PlanCosts;
import com.cryptopilot.trading.model.PlanSnapshot;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.RiskInput;
import com.cryptopilot.trading.model.TradeAmounts;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Maps plans and calculations to the API's responses; nothing is decided here. */
final class PlanResponses {

    private PlanResponses() {}

    /** A plan in full; reads its warnings. {@code position} is the position it opened, or {@code null}. */
    static TradingPlanResponse detail(TradingPlan plan, PositionResponse position) {
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
                history(plan),
                position);
    }

    /**
     * The position of an executed plan from its journal record: the Futures liquidation price from the plan's snapshot
     * and, while it is open, the unrealized P/L at {@code currentPrice} without fees (Q-55).
     *
     * @param currentPrice the Futures mark price or the Spot last price; empty when closed or not cached
     */
    static PositionResponse position(TradingPlan plan, RecordedPosition position, Optional<BigDecimal> currentPrice) {
        return position(plan, position, currentPrice, null, Optional.empty(), List.of());
    }

    /**
     * The position with its levels (TP-E): the current and original stop loss and take profit, the history of their
     * changes, the true breakeven stop while OPEN, and the warnings of a change just made.
     *
     * <p>Rule: BR-35, BR-38, BR-42; D-124.
     *
     * @param levels the levels the journal holds; {@code null} shows the plan's own levels and no change
     * @param breakeven the breakeven stop of an OPEN position; empty when CLOSED or not computed
     * @param levelWarnings the warnings of the change this response answers; empty on a read
     */
    static PositionResponse position(
            TradingPlan plan,
            RecordedPosition position,
            Optional<BigDecimal> currentPrice,
            PositionLevels levels,
            Optional<Breakeven> breakeven,
            List<PlanWarning> levelWarnings) {
        BigDecimal current = position.status() == PositionStatus.OPEN ? currentPrice.orElse(null) : null;
        TradeAmounts amounts = position.amounts();
        NetPnl net = amounts == null ? null : amounts.netPnl();
        Optional<Breakeven> open = position.status() == PositionStatus.OPEN ? breakeven : Optional.empty();
        return new PositionResponse(
                position.status(),
                position.entryPrice(),
                position.quantity(),
                position.entryTime(),
                position.entryFee(),
                position.liquidationPrice(),
                current,
                current == null
                        ? null
                        : TradeResultCalculator.unrealizedPnl(
                                plan.getDirection(), position.entryPrice(), current, position.quantity()),
                position.exitPrice(),
                position.exitTime(),
                position.closeReason(),
                position.realizedPnl(),
                position.feeAmount(),
                position.fundingFeeAmount(),
                net == null ? null : net.amount(),
                net != null && net.provisional(),
                levels == null ? plan.getStopLoss() : levels.stopLoss(),
                levels == null ? plan.getTakeProfit() : levels.takeProfit(),
                levels == null ? plan.getStopLoss() : levels.originalStopLoss(),
                levels == null ? plan.getTakeProfit() : levels.originalTakeProfit(),
                levels != null && levels.stopWidened(),
                levels == null ? 0 : levels.changeCount(),
                levels == null
                        ? List.of()
                        : levels.changes().stream()
                                .map(change -> new PositionLevelChangeResponse(
                                        change.changedAt(),
                                        change.oldStopLoss(),
                                        change.oldTakeProfit(),
                                        change.newStopLoss(),
                                        change.newTakeProfit()))
                                .toList(),
                open.map(Breakeven::price).orElse(null),
                open.map(Breakeven::provisional).orElse(false),
                warnings(levelWarnings));
    }

    /** A row of the list; the warnings are not read. {@code positionStatus} is its position's, or {@code null}. */
    static TradingPlanSummaryResponse summary(TradingPlan plan, PositionStatus positionStatus) {
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
                plan.getInvalidationReason(),
                positionStatus);
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
