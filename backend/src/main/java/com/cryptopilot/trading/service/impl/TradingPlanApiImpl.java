package com.cryptopilot.trading.service.impl;

import com.cryptopilot.trading.PlanContext;
import com.cryptopilot.trading.TradingPlanApi;
import com.cryptopilot.trading.dto.response.PlanSnapshotResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.service.TradingPlanService;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads the plan through {@link TradingPlanService#get}, so the ownership check and the stored risk panel are the
 * ones SCR-17 shows, and keeps only what the AI Assistant may see (BR-51).
 *
 * <p>Rule: BR-51; TECHNICAL_DESIGN 7.14.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class TradingPlanApiImpl implements TradingPlanApi {

    private final TradingPlanService plans;

    @Override
    public PlanContext planContext(UUID userId, UUID planId) {
        return of(plans.get(userId, planId));
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
