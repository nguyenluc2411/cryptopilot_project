package com.cryptopilot.journal.service.impl;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.journal.dto.request.PerformanceFilterRequest;
import com.cryptopilot.journal.dto.response.EquityPointResponse;
import com.cryptopilot.journal.dto.response.PerformanceAdvancedResponse;
import com.cryptopilot.journal.dto.response.PerformanceBreakdownResponse;
import com.cryptopilot.journal.dto.response.PerformanceSummaryResponse;
import com.cryptopilot.journal.model.PerformanceCounts;
import com.cryptopilot.journal.model.PerformanceDetail;
import com.cryptopilot.journal.model.PerformanceFilter;
import com.cryptopilot.journal.model.PerformanceGroup;
import com.cryptopilot.journal.model.enums.PerformanceMarket;
import com.cryptopilot.journal.model.enums.PerformanceSource;
import com.cryptopilot.journal.repository.PerformanceQueryRepository;
import com.cryptopilot.journal.service.PerformanceService;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.trading.StrategyCatalog;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * Answers SCR-23: resolves the period, applies the plan gate at request time and names the groups; the figures come
 * from SQL. Days are those of UTC+7, as the clients show them. A period is {@code [from, to)} of any length: without
 * {@code from} it starts at the first trade, without {@code to} it ends with today.
 *
 * <p>Rule: SRS 3.6.3; UC-25; BR-41, BR-42, BR-62; CR-08; D-58, D-59.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PerformanceServiceImpl implements PerformanceService {

    private static final String UNASSIGNED = "Unassigned";
    private static final String ZONE_NAME = "Asia/Ho_Chi_Minh";
    private static final ZoneId ZONE = ZoneId.of(ZONE_NAME);
    private static final int FIRST_YEAR = 1;
    private static final int LAST_YEAR = 9999;

    private final PerformanceQueryRepository statistics;
    private final EntitlementApi entitlements;
    private final MarketApi market;
    private final StrategyCatalog strategies;
    private final Clock clock;

    @Override
    public PerformanceSummaryResponse summary(UUID userId, PerformanceFilterRequest request) {
        LocalDate to = to(request);
        LocalDate from = from(request, to);
        PerformanceFilter filter = filter(userId, request, from, to);
        if (!entitlements.hasFeature(userId, Feature.ADVANCED_PERFORMANCE)) {
            return summary(from, to, statistics.counts(filter), null);
        }
        PerformanceDetail detail = statistics.detail(filter);
        return summary(from, to, detail.counts(), advanced(detail));
    }

    @Override
    public List<PerformanceBreakdownResponse> byCoin(UUID userId, PerformanceFilterRequest request) {
        List<PerformanceGroup> groups = statistics.byPair(gated(userId, request));
        List<UUID> pairIds =
                groups.stream().map(group -> UUID.fromString(group.key())).toList();
        Map<String, String> symbols = market.pairListings(pairIds).stream()
                .collect(Collectors.toMap(pair -> pair.pairId().toString(), PairListing::symbol));
        return breakdown(groups, key -> symbols.getOrDefault(key, key));
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
        return statistics.equityCurve(gated(userId, request), ZONE_NAME).stream()
                .map(point ->
                        new EquityPointResponse(point.day(), point.trades(), point.netPnl(), point.cumulativeNetPnl()))
                .toList();
    }

    private PerformanceFilter gated(UUID userId, PerformanceFilterRequest request) {
        entitlements.requireFeature(userId, Feature.ADVANCED_PERFORMANCE);
        LocalDate to = to(request);
        return filter(userId, request, from(request, to), to);
    }

    private LocalDate to(PerformanceFilterRequest request) {
        if (request.to() == null) {
            return LocalDate.now(clock.withZone(ZONE)).plusDays(1);
        }
        requireStorableYear(request.to(), "to");
        return request.to();
    }

    /** {@code to} is the resolved end, so a {@code from} in the future is refused without a {@code to} too (D-139). */
    private static LocalDate from(PerformanceFilterRequest request, LocalDate to) {
        LocalDate from = request.from();
        if (from == null) {
            return null;
        }
        requireStorableYear(from, "from");
        if (!from.isBefore(to)) {
            throw new FieldValidationException("the period starts at or after its end", Map.of("from", "MSG15"));
        }
        return from;
    }

    private static void requireStorableYear(LocalDate day, String field) {
        if (day.getYear() < FIRST_YEAR || day.getYear() > LAST_YEAR) {
            throw new FieldValidationException("the year is out of range", Map.of(field, "MSG15"));
        }
    }

    private static PerformanceFilter filter(
            UUID userId, PerformanceFilterRequest request, LocalDate from, LocalDate to) {
        return new PerformanceFilter(
                userId,
                from == null ? null : from.atStartOfDay(ZONE).toInstant(),
                to.atStartOfDay(ZONE).toInstant(),
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
