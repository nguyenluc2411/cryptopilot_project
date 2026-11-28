package com.cryptopilot.trading.service.impl;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.trading.PlanContext;
import com.cryptopilot.trading.PlanOrderTerms;
import com.cryptopilot.trading.SimulatedFillLearning;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.TradingPlanApi;
import com.cryptopilot.trading.dto.response.PlanSnapshotResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.event.PlanFilled;
import com.cryptopilot.trading.event.SetupScoreDropped;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.TradingPlanService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the plan through {@link TradingPlanService#get}, so the ownership check and the stored risk panel are the
 * ones SCR-17 shows, and keeps only what the AI Assistant may see (BR-51). Gives paper trading the terms an ACTIVE
 * plan is applied with, and sets the plan EXECUTED when that order fills (RF-33).
 *
 * <p>Rule: BR-33, BR-51; TECHNICAL_DESIGN 7.14; RF-33.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class TradingPlanApiImpl implements TradingPlanApi {

    private static final Logger log = LoggerFactory.getLogger(TradingPlanApiImpl.class);

    private final TradingPlanService plans;
    private final TradingPlanRepository stored;
    private final MarketApi market;
    private final ApplicationEventPublisher events;
    private final TradeJournal journal;
    private final FillLearningCapture learning;
    private final Clock clock;

    @Override
    public PlanContext planContext(UUID userId, UUID planId) {
        return of(plans.get(userId, planId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlanOrderTerms> terms(UUID planId) {
        return stored.findById(planId).map(TradingPlanApiImpl::termsOf);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean entryFilled(UUID planId, UUID orderId, boolean buy, BigDecimal price, boolean maker, Instant at) {
        // Row-locked, as a cancel is: of a fill and a cancel, the first to commit decides (NSF-07). The plan changes on
        // its entity, never by an update that clears the persistence context: the paper fill this runs inside keeps
        // the wallet's balances there, and they must still be written (RF-33).
        Optional<TradingPlan> found = stored.lockById(planId);
        if (found.isEmpty()
                || (found.get().getDirection() == Direction.LONG) != buy
                || found.get().getStatus() != PlanStatus.ACTIVE) {
            return false;
        }
        LiquidityRole liquidity = maker ? LiquidityRole.MAKER : LiquidityRole.TAKER;
        TradingPlan plan = found.get();
        plan.markExecuted(at, price, liquidity);
        log.info("RF-33 plan {} executed by paper order {} at {} ({})", planId, orderId, price, liquidity);
        String symbol = market.tradablePair(plan.getPairId(), plan.getMarket())
                .map(TradablePair::symbol)
                .orElse(null);
        // BR-70: the fill's learning data goes into the record the paper fill opened just before.
        SimulatedFillLearning fillLearning = learningOf(plan);
        UUID positionId = journal.recordFillLearning(planId, fillLearning).orElse(orderId);
        events.publishEvent(new PlanFilled(
                planId,
                orderId,
                plan.getUserId(),
                plan.getMarket(),
                plan.getDirection(),
                symbol,
                price,
                plan.getSnapshot().positionQuantity(),
                liquidity,
                at));
        if (Boolean.TRUE.equals(fillLearning.setupScoreDropWarned())) {
            log.info(
                    "BR-70 plan {} filled with setup score {}, down from {}",
                    planId,
                    fillLearning.setupScoreAtFill(),
                    fillLearning.setupScoreAtPlan());
            events.publishEvent(new SetupScoreDropped(
                    planId,
                    positionId,
                    plan.getUserId(),
                    plan.getDirection(),
                    symbol,
                    fillLearning.setupScoreAtPlan(),
                    fillLearning.setupScoreAtFill(),
                    plan.getExecutedAt()));
        }
        return true;
    }

    /** The learning data of the fill; whatever goes wrong taking it, the fill goes on without it (BR-70). */
    private SimulatedFillLearning learningOf(TradingPlan plan) {
        try {
            return learning.of(plan);
        } catch (RuntimeException e) {
            log.warn("BR-70 plan {} filled without learning data: {}", plan.getId(), e.toString());
            return SimulatedFillLearning.none();
        }
    }

    static PlanOrderTerms termsOf(TradingPlan plan) {
        return new PlanOrderTerms(
                plan.getId(),
                plan.getUserId(),
                plan.getPairId(),
                plan.getMarket(),
                plan.getDirection(),
                plan.getEntryType(),
                plan.getEntryPrice(),
                plan.getStopLoss(),
                plan.getTakeProfit(),
                plan.getSnapshot().positionQuantity(),
                plan.getLeverage(),
                plan.getMarginMode());
    }

    static PlanContext of(TradingPlanResponse plan) {
        PlanSnapshotResponse risk = plan.snapshot();
        return new PlanContext(
                plan.pairId(),
                plan.market(),
                plan.direction(),
                plan.entryType(),
                plan.entryPrice(),
                plan.stopLoss(),
                plan.takeProfit(),
                plan.riskPercent(),
                plan.leverage(),
                plan.marginMode(),
                plan.analysisTimeframe(),
                plan.status(),
                plan.setupScoreAtPlan(),
                plan.setupScorePreset(),
                risk == null ? null : risk.riskRewardRatio(),
                risk == null ? null : risk.netRiskReward(),
                risk == null ? null : risk.feeShareOfRisk(),
                risk == null ? null : risk.estimatedLiquidationPrice(),
                plan.warnings() == null
                        ? null
                        : plan.warnings().stream()
                                .map(w -> new PlanContext.Warning(w.type(), w.severity(), w.message()))
                                .toList());
    }
}
