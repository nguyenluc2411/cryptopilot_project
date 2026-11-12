package com.cryptopilot.journal.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.journal.client.ChartImageStorage;
import com.cryptopilot.journal.dto.request.ManualTradeRequest;
import com.cryptopilot.journal.dto.response.JournalEntryDetailResponse;
import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.model.enums.TradeSource;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import com.cryptopilot.journal.service.JournalService;
import com.cryptopilot.journal.service.ManualTradeService;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.trading.StrategyCatalog;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records, edits and deletes manual trades. A trade with an exit is CLOSED by MANUAL, with the realized P/L of BR-35
 * from its prices and the fees the Trader entered, 0 when left out, so the statistics count it (D-139); one without an
 * exit is OPEN. The record keeps source MANUAL, so the list and the statistics can show it apart from the simulated
 * trades (BR-41). A simulated trade is never edited or deleted here: its execution facts are fixed (BR-40).
 *
 * <p>Rule: BR-22, BR-35, BR-38, BR-40, BR-41; UC-24; SRS 3.6.2; D-97, D-139.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley, "Service
 * Layer".
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ManualTradeServiceImpl implements ManualTradeService {

    private static final Logger log = LoggerFactory.getLogger(ManualTradeServiceImpl.class);

    private final TradingJournalRepository journal;
    private final JournalService entries;
    private final StrategyCatalog strategies;
    private final MarketApi market;
    private final ChartImageStorage images;
    private final Clock clock;

    @Override
    @Transactional
    public JournalEntryDetailResponse record(UUID userId, ManualTradeRequest request) {
        validate(request);
        UUID id = UuidV7.next();
        Instant now = clock.instant();
        BigDecimal realized = ManualTradeRules.realizedPnl(request);
        journal.insertManual(
                id,
                userId,
                request.pairId(),
                request.strategyId(),
                request.market().name(),
                request.direction().name(),
                request.leverage(),
                request.entryPrice(),
                request.stopLoss(),
                request.takeProfit(),
                request.quantity(),
                micros(request.entryTime()),
                request.exitPrice(),
                micros(request.exitTime()),
                closeReason(realized),
                fee(request),
                request.fundingFeeAmount(),
                realized,
                status(realized),
                blankToNull(request.setupDescription()),
                blankToNull(request.lessonsLearned()),
                blankToNull(request.mistakes()),
                now);
        log.info("UC-24 manual trade {} recorded", id);
        return entries.detail(userId, id);
    }

    @Override
    @Transactional
    public JournalEntryDetailResponse edit(UUID userId, UUID journalId, ManualTradeRequest request) {
        requireManual(userId, journalId);
        validate(request);
        BigDecimal realized = ManualTradeRules.realizedPnl(request);
        journal.updateManual(
                journalId,
                userId,
                request.pairId(),
                request.strategyId(),
                request.market().name(),
                request.direction().name(),
                request.leverage(),
                request.entryPrice(),
                request.stopLoss(),
                request.takeProfit(),
                request.quantity(),
                micros(request.entryTime()),
                request.exitPrice(),
                micros(request.exitTime()),
                closeReason(realized),
                fee(request),
                request.fundingFeeAmount(),
                realized,
                status(realized),
                blankToNull(request.setupDescription()),
                blankToNull(request.lessonsLearned()),
                blankToNull(request.mistakes()),
                clock.instant());
        log.info("UC-24 manual trade {} edited", journalId);
        return entries.detail(userId, journalId);
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID journalId) {
        TradingJournal record = requireManual(userId, journalId);
        journal.deleteManual(journalId, userId);
        if (record.getChartImagePublicId() != null) {
            destroyAfterCommit(record.getChartImagePublicId());
        }
        log.info("UC-24 manual trade {} deleted", journalId);
    }

    /** The user's record if it is a manual one: 404 for an unknown id or another's, 409 for a simulated trade. */
    private TradingJournal requireManual(UUID userId, UUID journalId) {
        TradingJournal record = journal.findByIdAndUserId(journalId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("TradingJournal", journalId));
        if (record.getSource() != TradeSource.MANUAL) {
            throw new BusinessException(
                    ErrorCode.DATA_CONFLICT,
                    "BR-40 the execution facts of simulated trade " + journalId + " cannot be changed");
        }
        return record;
    }

    private void validate(ManualTradeRequest request) {
        Optional<PairListing> pair =
                market.pairListings(Set.of(request.pairId())).stream().findFirst();
        Map<String, String> errors = ManualTradeRules.violations(request, pair, clock.instant());
        if (request.strategyId() != null
                && !strategies.names(Set.of(request.strategyId())).containsKey(request.strategyId())) {
            errors.put("strategyId", "MSG15");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException("UC-24 the manual trade breaks " + errors.keySet(), errors);
        }
    }

    private static BigDecimal fee(ManualTradeRequest request) {
        // D-139: a manual trade stores 0 rather than no fee, so it is never left out of the statistics.
        return Objects.requireNonNullElse(request.feeAmount(), BigDecimal.ZERO);
    }

    private static String closeReason(BigDecimal realized) {
        return realized == null ? null : CloseReason.MANUAL.name();
    }

    private static String status(BigDecimal realized) {
        return (realized == null ? PositionStatus.OPEN : PositionStatus.CLOSED).name();
    }

    /** To the microsecond, like the column, so the trade reads back as it was entered. */
    private static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    private void destroyAfterCommit(String publicId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    images.destroy(publicId);
                } catch (RuntimeException failed) {
                    log.warn(
                            "BR-41 chart image {} of a deleted trade was not deleted: {}", publicId, failed.toString());
                }
            }
        });
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
