package com.cryptopilot.ai.service.impl;

import com.cryptopilot.ai.model.AiContextSelection;
import com.cryptopilot.ai.model.ContextSnapshot;
import com.cryptopilot.ai.model.PerformanceContext;
import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.journal.JournalApi;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairAnalysis;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.PlanContext;
import com.cryptopilot.trading.TradingPlanApi;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the context snapshot of a question (T-074, SRS 3.9.1): the chosen pair's analysis as the Trader sees it on
 * SCR-10 / SCR-11 (indicators, levels, setup score and its components when the plan shows them, Futures metrics), the
 * chosen plan's terms, ratios and warnings, and on PREMIUM the performance summary of the journal (T-051) with the
 * D-111 learning metrics, as aggregates only.
 *
 * <p>No personal data goes in (BR-51): the snapshot types have no field for an email, a name, a user id, the capital
 * or the Trader's note, so there is nothing to strip. The user id is used here only to read what the Trader may see.
 *
 * <p>The plan, when chosen, supplies the pair, market and timeframe the Trader did not pick, so "explain my plan"
 * comes with the analysis of the plan's own series.
 *
 * <p>Rule: BR-51, BR-62 ({@code AI_PERFORMANCE_CONTEXT}), BR-70; TECHNICAL_DESIGN 7.14 step 1; D-111.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ContextSnapshotBuilder {

    private final MarketApi market;
    private final TradingPlanApi plans;
    private final JournalApi journal;
    private final EntitlementApi entitlements;
    private final JsonMapper json;
    private final Clock clock;

    /**
     * The snapshot of what the Trader chose.
     *
     * @throws ResourceNotFoundException when the plan is not the Trader's or the pair is not enabled on the market
     * @throws com.cryptopilot.common.exception.BusinessException {@code PLAN_FEATURE_NOT_INCLUDED} (MSG29) for the
     *     performance summary below PREMIUM or a Futures pair without {@code FUTURES_ANALYSIS}; {@code
     *     VALIDATION_FAILED} (MSG01) for an unknown timeframe
     */
    public ContextSnapshot build(UUID userId, AiContextSelection selection) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(selection, "selection");
        PlanContext plan = selection.planId() == null ? null : plans.planContext(userId, selection.planId());
        PerformanceContext performance = null;
        if (selection.includePerformance()) {
            entitlements.requireFeature(userId, Feature.AI_PERFORMANCE_CONTEXT);
            performance = PerformanceContext.of(journal.performanceDigest(userId));
        }
        UUID pairId = selection.pairId() != null ? selection.pairId() : plan == null ? null : plan.pairId();
        PairAnalysis analysis = null;
        if (pairId != null) {
            MarketType marketType = marketOf(selection, plan, pairId);
            String timeframe = selection.timeframe() != null
                    ? selection.timeframe()
                    : plan != null && pairId.equals(plan.pairId()) ? plan.analysisTimeframe() : null;
            analysis = market.pairAnalysis(pairId, marketType, timeframe, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", pairId));
        }
        return new ContextSnapshot(ContextSnapshot.VERSION, clock.instant(), analysis, plan, performance);
    }

    /** The snapshot as stored in {@code ai_message.context_snapshot}, or {@code null} when there is nothing in it. */
    public String toJson(ContextSnapshot snapshot) {
        return snapshot == null || snapshot.carriesNothing() ? null : json.writeValueAsString(snapshot);
    }

    private static MarketType marketOf(AiContextSelection selection, PlanContext plan, UUID pairId) {
        if (selection.market() != null) {
            return selection.market();
        }
        if (plan != null && pairId.equals(plan.pairId())) {
            return plan.market();
        }
        return MarketType.SPOT;
    }
}
