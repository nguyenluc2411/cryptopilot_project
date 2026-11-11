package com.cryptopilot.journal.service.impl;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.journal.dto.request.PerformanceFilterRequest;
import com.cryptopilot.journal.dto.response.EquityPointResponse;
import com.cryptopilot.journal.dto.response.PerformanceAdvancedResponse;
import com.cryptopilot.journal.dto.response.PerformanceBreakdownResponse;
import com.cryptopilot.journal.dto.response.PerformanceSummaryResponse;
import com.cryptopilot.journal.model.PerformanceCounts;
import com.cryptopilot.journal.model.PerformanceDetail;
import com.cryptopilot.journal.model.PerformanceFilter;
import com.cryptopilot.journal.model.PerformanceGroup;
import com.cryptopilot.journal.model.TradePeriod;
import com.cryptopilot.journal.model.enums.PerformanceMarket;
import com.cryptopilot.journal.model.enums.PerformanceSource;
import com.cryptopilot.journal.repository.PerformanceQueryRepository;
import com.cryptopilot.journal.service.PerformanceService;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.trading.StrategyCatalog;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Answers SCR-23: resolves the period with {@link TradePeriods}, applies the plan gate at request time and names the
 * groups; the figures come from SQL. A period without {@code from} starts at the first trade.
 *
 * <p>Rule: SRS 3.6.3; UC-25; BR-41, BR-42, BR-62; CR-08; D-58, D-59.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PerformanceServiceImpl implements PerformanceService {

    private static final String UNASSIGNED = "Unassigned";

    private final PerformanceQueryRepository statistics;
    private final EntitlementApi entitlements;
    private final MarketApi market;
    private final StrategyCatalog strategies;
    private final Clock clock;

    @Override
    public PerformanceSummaryResponse summary(UUID userId, PerformanceFilterRequest request) {
        TradePeriod period = TradePeriods.resolve(request.from(), request.to(), clock);
        PerformanceFilter filter = filter(userId, request, period);
        if (!entitlements.hasFeature(userId, Feature.ADVANCED_PERFORMANCE)) {
            return summary(period.from(), period.to(), statistics.counts(filter), null);
        }
        PerformanceDetail detail = statistics.detail(filter);
        return summary(period.from(), period.to(), detail.counts(), advanced(detail));
    }

    @Override
    public List<PerformanceBreakdownResponse> byCoin(UUID userId, PerformanceFilterRequest request) {
        List<PerformanceGroup> groups = statistics.byPair(gated(userId, request));
        List<UUID> pairIds =
                groups.stream().map(group -> UUID.fromString(group.key())).toList();
        Map<String, String> symbols = market.pairListings(pairIds).stream()
                .collect(Collectors.toMap(pair -> pair.pairId().toString(), PairListing::symbol));
        return breakdown(groups, symbols::get);
    }

    @Override
    public List<PerformanceBreakdownResponse> byStrategy(UUID userId, PerformanceFilterRequest request) {
        List<PerformanceGroup> groups = statistics.byStrategy(gated(userId, request));
        List<UUID> strategyIds = groups.stream()
                .map(PerformanceGroup::key)
                .filter(Objects::nonNull)
                .map(UUID::fromString)
                .toList();
        Map<UUID, String> names = strategies.names(strategyIds);
        return breakdown(groups, key -> key == null ? UNASSIGNED : names.getOrDefault(UUID.fromString(key), key));
    }

    @Override
    public List<PerformanceBreakdownResponse> byMarket(UUID userId, PerformanceFilterRequest request) {
        return breakdown(statistics.byMarket(gated(userId, request)), Function.identity());
    }

    @Override
    public List<EquityPointResponse> equityCurve(UUID userId, PerformanceFilterRequest request) {
        return statistics.equityCurve(gated(userId, request), TradePeriods.ZONE_NAME).stream()
                .map(point ->
                        new EquityPointResponse(point.day(), point.trades(), point.netPnl(), point.cumulativeNetPnl()))
                .toList();
    }

    private PerformanceFilter gated(UUID userId, PerformanceFilterRequest request) {
        entitlements.requireFeature(userId, Feature.ADVANCED_PERFORMANCE);
        return filter(userId, request, TradePeriods.resolve(request.from(), request.to(), clock));
    }

    private static PerformanceFilter filter(UUID userId, PerformanceFilterRequest request, TradePeriod period) {
        return new PerformanceFilter(
                userId,
                period.start(),
                period.end(),
                request.market() != null ? request.market() : PerformanceMarket.ALL,
                request.source() != null ? request.source() : PerformanceSource.SIMULATED,
                request.pairId(),
                request.strategyId());
    }

    private static PerformanceSummaryResponse summary(
            LocalDate from, LocalDate to, PerformanceCounts counts, PerformanceAdvancedResponse advanced) {
        return new PerformanceSummaryResponse(
                from,
                to,
                counts.trades(),
                counts.wins(),
                counts.losses(),
                counts.breakeven(),
                counts.winRate(),
                counts.totalNetPnl(),
                counts.provisionalTrades(),
                counts.totalFees(),
                counts.totalFunding(),
                advanced);
    }

    private static PerformanceAdvancedResponse advanced(PerformanceDetail detail) {
        return new PerformanceAdvancedResponse(
                detail.averageWin(),
                detail.averageLoss(),
                detail.profitFactor(),
                detail.averagePlannedRiskReward(),
                detail.averageRealizedR(),
                detail.tradesWithR(),
                detail.largestWin(),
                detail.largestLoss(),
                detail.maxConsecutiveLosses(),
                detail.maxDrawdown(),
                detail.averageEntryDeviationR(),
                detail.costOfLateEntryR(),
                detail.averageMaeR(),
                detail.averageMfeR());
    }

    private static List<PerformanceBreakdownResponse> breakdown(
            List<PerformanceGroup> groups, Function<String, String> label) {
        return groups.stream()
                .map(group -> new PerformanceBreakdownResponse(
                        group.key(),
                        label.apply(group.key()),
                        group.counts().trades(),
                        group.counts().wins(),
                        group.counts().losses(),
                        group.counts().breakeven(),
                        group.counts().winRate(),
                        group.counts().totalNetPnl(),
                        group.profitFactor(),
                        group.averageRealizedR()))
                .toList();
    }
}
