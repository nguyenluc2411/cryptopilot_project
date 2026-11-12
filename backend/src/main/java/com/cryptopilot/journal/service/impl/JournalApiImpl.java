package com.cryptopilot.journal.service.impl;

import com.cryptopilot.journal.JournalApi;
import com.cryptopilot.journal.PerformanceDigest;
import com.cryptopilot.journal.model.PerformanceCounts;
import com.cryptopilot.journal.model.PerformanceDetail;
import com.cryptopilot.journal.model.PerformanceFilter;
import com.cryptopilot.journal.model.TradePeriod;
import com.cryptopilot.journal.model.enums.PerformanceMarket;
import com.cryptopilot.journal.model.enums.PerformanceSource;
import com.cryptopilot.journal.repository.PerformanceQueryRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads the digest with the statement of the SCR-23 dashboard, over the dashboard's default trades (every market,
 * simulated, from the first trade to the end of today), so the AI Assistant explains the figures the Trader sees there.
 * The repository is read directly rather than through {@code PerformanceService.summary}, whose full metrics depend on
 * {@code ADVANCED_PERFORMANCE}: the assistant's own gate is {@code AI_PERFORMANCE_CONTEXT}, checked by the caller.
 *
 * <p>Rule: BR-42, BR-51, BR-62, BR-70; SRS 3.6.3; D-58, D-111.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class JournalApiImpl implements JournalApi {

    private final PerformanceQueryRepository statistics;
    private final Clock clock;

    @Override
    public PerformanceDigest performanceDigest(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        TradePeriod period = TradePeriods.resolve(null, null, clock);
        PerformanceDetail detail = statistics.detail(new PerformanceFilter(
                userId, period.start(), period.end(), PerformanceMarket.ALL, PerformanceSource.SIMULATED, null, null));
        PerformanceCounts counts = detail.counts();
        return new PerformanceDigest(
                counts.trades(),
                counts.wins(),
                counts.losses(),
                counts.breakeven(),
                counts.winRate(),
                counts.totalNetPnl(),
                counts.totalFees(),
                counts.totalFunding(),
                counts.provisionalTrades(),
                detail.profitFactor(),
                detail.averageRealizedR(),
                detail.tradesWithR(),
                detail.averageEntryDeviationR(),
                detail.costOfLateEntryR(),
                detail.averageMaeR(),
                detail.averageMfeR());
    }
}
