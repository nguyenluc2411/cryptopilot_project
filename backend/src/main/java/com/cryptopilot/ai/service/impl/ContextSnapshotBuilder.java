package com.cryptopilot.ai.service.impl;

import com.cryptopilot.ai.AssistantNewsSource;
import com.cryptopilot.ai.model.AiContextSelection;
import com.cryptopilot.ai.model.ContextSnapshot;
import com.cryptopilot.ai.model.NewsContext;
import com.cryptopilot.ai.model.PerformanceContext;
import com.cryptopilot.ai.model.TraderContext;
import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.journal.FillLearning;
import com.cryptopilot.journal.FillLearningReader;
import com.cryptopilot.journal.JournalApi;
import com.cryptopilot.journal.PerformanceDigest;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairAnalysis;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.PlanContext;
import com.cryptopilot.trading.TradingPlanApi;
import com.cryptopilot.user.UserApi;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the context snapshot of a question (T-074, SRS 3.9.1): the chosen pair's analysis as the Trader sees it on
 * SCR-10 / SCR-11 (indicators, levels, setup score and its components when the plan shows them, Futures metrics), the
 * chosen plan's terms, ratios and warnings, and on PREMIUM the performance summary of the journal (T-051) with the
 * D-111 learning metrics, as aggregates, and the learning fields of the latest closed trades (T-074b). With a pair,
 * the recent news about its coin and about the market, so the answer weighs what happened lately and cites it; with
 * anything chosen at all, the Trader's trading style and risk profile, so it speaks to the way they trade. A general
 * question, with nothing chosen, carries nothing.
 *
 * <p>No personal data goes in (BR-51): the snapshot types have no field for an email, a name, a user id, the capital
 * or the Trader's note, so there is nothing to strip. The user id is used here only to read what the Trader may see.
 *
 * <p>The plan, when chosen, supplies the pair, market and timeframe the Trader did not pick, so "explain my plan"
 * comes with the analysis of the plan's own series.
 *
 * <p>Rule: BR-51, BR-62 ({@code AI_PERFORMANCE_CONTEXT}), BR-69, BR-70; TECHNICAL_DESIGN 7.14 step 1; D-111 (M4);
 * T-074, T-074b.
 *
 * <p>Reference: Liu, N. F. et al. (2024). Lost in the Middle: How Language Models Use Long Contexts. <i>Transactions of
 * the Association for Computational Linguistics</i> 12, 157–173 (a bounded context).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ContextSnapshotBuilder {

    /**
     * How many of the latest closed trades go with the performance summary, bounded by the 20,000 characters the AI
     * Service accepts as context (NSF-16).
     */
    static final int RECENT_TRADES = 10;

    private final MarketApi market;
    private final TradingPlanApi plans;
    private final JournalApi journal;
    private final FillLearningReader learning;
    private final AssistantNewsSource news;
    private final UserApi users;
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
            PerformanceDigest digest = journal.performanceDigest(userId);
            performance = PerformanceContext.of(digest, digest.trades() > 0 ? recentTrades(userId) : List.of());
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
        NewsContext recent = pairId == null ? null : NewsContext.of(news.recentNews(pairId));
        TraderContext trader = AiContextSelection.NONE.equals(selection)
                ? null
                : users.tradingProfileOf(userId).map(TraderContext::of).orElse(null);
        return new ContextSnapshot(
                ContextSnapshot.VERSION, clock.instant(), analysis, plan, performance, recent, trader);
    }

    /** The snapshot as stored in {@code ai_message.context_snapshot}, or {@code null} when there is nothing in it. */
    public String toJson(ContextSnapshot snapshot) {
        return snapshot == null || snapshot.carriesNothing() ? null : json.writeValueAsString(snapshot);
    }

    private List<PerformanceContext.TradeLearning> recentTrades(UUID userId) {
        List<FillLearning> trades = learning.latestClosed(userId, RECENT_TRADES);
        if (trades.isEmpty()) {
            return List.of();
        }
        Set<UUID> pairIds = trades.stream().map(FillLearning::pairId).collect(Collectors.toSet());
        // pairListings also knows a delisted or disabled pair; an unknown one just has no symbol.
        Map<UUID, String> symbols = market.pairListings(pairIds).stream()
                .collect(Collectors.toMap(PairListing::pairId, PairListing::symbol, (first, same) -> first));
        return trades.stream()
                .map(trade -> PerformanceContext.TradeLearning.of(trade, symbols.get(trade.pairId())))
                .toList();
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
