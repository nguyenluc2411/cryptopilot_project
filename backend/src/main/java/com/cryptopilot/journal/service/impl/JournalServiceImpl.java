package com.cryptopilot.journal.service.impl;

import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.journal.dto.response.JournalEntryResponse;
import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.model.JournalListQuery;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import com.cryptopilot.journal.service.JournalService;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.TradeNetPnl;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Reads the journal page by page. The net P/L of a closed trade is {@link TradeNetPnl}, the formula the plan detail
 * shows the same position with, so both screens always agree; it is provisional while the funding is incomplete.
 *
 * <p>Rule: SRS 3.6.1; UC-22; BR-38; D-97 and its note of 2026-10-24; CR-04.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class JournalServiceImpl implements JournalService {

    private static final UUID NO_PAIR = new UUID(0L, 0L);

    private final TradingJournalRepository journal;

    @Override
    public PageResponse<JournalEntryResponse> list(UUID userId, JournalListQuery query) {
        Set<PositionStatus> statuses =
                query.status() == null ? EnumSet.allOf(PositionStatus.class) : EnumSet.of(query.status());
        Set<MarketType> markets = query.market() == null ? EnumSet.allOf(MarketType.class) : EnumSet.of(query.market());
        return PageResponse.of(
                journal.search(
                        userId,
                        statuses,
                        markets,
                        query.pairId() == null,
                        query.pairId() == null ? NO_PAIR : query.pairId(),
                        Paging.of(query.page(), query.pageSize())),
                JournalServiceImpl::entry);
    }

    private static JournalEntryResponse entry(TradingJournal record) {
        boolean closed = record.getStatus() == PositionStatus.CLOSED;
        // A closed manual trade may lack its amounts: the schema demands them of a simulated one only.
        BigDecimal net = closed && record.getRealizedPnl() != null && record.getFeeAmount() != null
                ? TradeNetPnl.of(record.getRealizedPnl(), record.getFeeAmount(), record.getFundingFeeAmount())
                : null;
        return new JournalEntryResponse(
                record.getId(),
                record.getPlanId(),
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
