package com.cryptopilot.journal.service.impl;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.journal.dto.response.JournalEntryDetailResponse;
import com.cryptopilot.journal.dto.response.JournalEntryResponse;
import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.model.JournalListQuery;
import com.cryptopilot.journal.model.TradePeriod;
import com.cryptopilot.journal.model.enums.PerformanceMarket;
import com.cryptopilot.journal.model.enums.PerformanceSource;
import com.cryptopilot.journal.model.enums.TradeSource;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import com.cryptopilot.journal.service.JournalService;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.StrategyCatalog;
import com.cryptopilot.trading.TradeNetPnl;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/**
 * Reads the journal page by page, and one trade with its review. The net P/L of a closed trade is {@link TradeNetPnl},
 * the formula the plan detail shows the same position with, so both screens always agree; it is provisional while the
 * funding is incomplete. The period of the list is that of {@link TradePeriods} applied to the entry time, the one time
 * an OPEN trade has. Ownership is part of the lookup of one trade: another Trader's is "not found" (MSG41) rather than
 * "forbidden", which would confirm that the id exists.
 *
 * <p>Rule: SRS 3.6.1; UC-22; BR-38, BR-41, BR-42; D-97 and its note of 2026-10-24; D-138, D-139, D-140; CR-04, CR-08.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class JournalServiceImpl implements JournalService {

    private static final UUID NO_ID = new UUID(0L, 0L);
    private static final int NO_RESULT = 0;

    private final TradingJournalRepository journal;
    private final StrategyCatalog strategies;
    private final Clock clock;

    @Override
    public PageResponse<JournalEntryResponse> list(UUID userId, JournalListQuery query) {
        TradePeriod period = TradePeriods.resolve(query.from(), query.to(), clock);
        Page<TradingJournal> records = journal.search(
                userId,
                query.status() == null
                        ? names(PositionStatus.values())
                        : Set.of(query.status().name()),
                markets(query.market()),
                sources(query.source()),
                period.start() == null ? Instant.EPOCH : period.start(),
                period.end(),
                query.pairId() == null,
                query.pairId() == null ? NO_ID : query.pairId(),
                query.strategyId() == null,
                query.strategyId() == null ? NO_ID : query.strategyId(),
                query.result() == null,
                query.result() == null ? NO_RESULT : query.result().getSign(),
                Paging.of(query.page(), query.pageSize()));
        Map<UUID, String> names = strategies.names(strategyIds(records.getContent()));
        return PageResponse.of(records, record -> entry(record, names));
    }

    @Override
    public JournalEntryDetailResponse detail(UUID userId, UUID journalId) {
        TradingJournal record = journal.findByIdAndUserId(journalId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("TradingJournal", journalId));
        return new JournalEntryDetailResponse(
                entry(record, strategies.names(strategyIds(List.of(record)))),
                record.getSetupDescription(),
                record.getLessonsLearned(),
                record.getMistakes(),
                record.getChartImageUrl());
    }

    private static Set<String> markets(PerformanceMarket market) {
        return market == null || market == PerformanceMarket.ALL ? names(MarketType.values()) : Set.of(market.name());
    }

    /** Both by default, unlike SCR-23, so the list of SCR-20 hides no trade (D-140). */
    private static Set<String> sources(PerformanceSource source) {
        return source == null || source == PerformanceSource.ALL ? names(TradeSource.values()) : Set.of(source.name());
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }

    private static Set<UUID> strategyIds(List<TradingJournal> records) {
        return records.stream()
                .map(TradingJournal::getStrategyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static JournalEntryResponse entry(TradingJournal record, Map<UUID, String> strategyNames) {
        boolean closed = record.getStatus() == PositionStatus.CLOSED;
        // A closed manual trade may lack its amounts: the schema demands them of a simulated one only.
        BigDecimal net = closed && record.getRealizedPnl() != null && record.getFeeAmount() != null
                ? TradeNetPnl.of(record.getRealizedPnl(), record.getFeeAmount(), record.getFundingFeeAmount())
                : null;
        return new JournalEntryResponse(
                record.getId(),
                record.getPlanId(),
                record.getStrategyId(),
                record.getStrategyId() == null ? null : strategyNames.get(record.getStrategyId()),
                record.getPairId(),
                record.getMarket(),
                record.getDirection(),
                record.getSource(),
                record.getStatus(),
                record.getLeverage(),
                record.getEntryPrice(),
                record.getQuantity(),
                record.getEntryTime(),
                record.getEntryFee(),
                record.getStopLoss(),
                record.getTakeProfit(),
                record.getExitPrice(),
                record.getExitTime(),
                record.getCloseReason(),
                record.getRealizedPnl(),
                record.getFeeAmount(),
                record.getFundingFeeAmount(),
                net,
                closed && record.isFundingIncomplete());
    }
}
