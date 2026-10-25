package com.cryptopilot.journal.service.impl;

import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.model.PlanPosition;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.SimulatedTradeClosing;
import com.cryptopilot.trading.SimulatedTradeOpening;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The journal's side of the plans' fills and exits: it writes the OPEN record inside the fill's transaction, closes it
 * with a compare-and-set inside the exit's, and answers what the plans and the matching engine need to know about
 * their positions. The plan's module computes every value; this class stores and reads.
 *
 * <p>Rule: BR-34, BR-35, BR-62; UC-20; D-63, D-97, D-119.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PersistentTradeJournal implements TradeJournal {

    private static final Logger log = LoggerFactory.getLogger(PersistentTradeJournal.class);

    private final TradingJournalRepository records;
    private final Clock clock;

    /** Joins the fill's transaction and never starts one: a record without its fill must not commit. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OpenTrade openSimulatedTrade(SimulatedTradeOpening opening) {
        TradingJournal record = records.saveAndFlush(TradingJournal.openSimulated(opening));
        log.info("BR-34 journal {} opened for plan {} at {}", record.getId(), opening.planId(), opening.entryPrice());
        return record.toOpenTrade();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpenTrade> openSimulatedTrades() {
        return records.findOpenSimulated().stream()
                .map(TradingJournal::toOpenTrade)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OpenTrade> openTrade(UUID positionId) {
        return records.findOpenSimulated(positionId).map(TradingJournal::toOpenTrade);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OpenTrade> openTradeOfPlan(UUID planId) {
        return records.findOpenSimulatedOfPlan(planId).map(TradingJournal::toOpenTrade);
    }

    /** Joins the exit's transaction and never starts one: the close and its event commit together. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean closeSimulatedTrade(SimulatedTradeClosing closing) {
        boolean closed = records.closeIfOpen(
                        closing.positionId(),
                        closing.version(),
                        closing.exitPrice(),
                        closing.exitTime(),
                        closing.reason().name(),
                        closing.realizedPnl(),
                        closing.feeAmount(),
                        closing.fundingFeeAmount(),
                        closing.fundingIncomplete(),
                        MissingFundingTimes.literal(closing.missingFundingTimes()),
                        clock.instant())
                == 1;
        if (closed) {
            log.info(
                    "BR-35 journal {} closed by {} at {}{}",
                    closing.positionId(),
                    closing.reason(),
                    closing.exitPrice(),
                    closing.fundingIncomplete() ? ", funding incomplete" : "");
        }
        return closed;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> openPositionPlans(UUID userId) {
        return new HashSet<>(records.findOpenPlanIds(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecordedPosition> positionOf(UUID planId) {
        return records.findByPlanId(planId).map(TradingJournal::toPosition);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PositionStatus> positionStatuses(Collection<UUID> planIds) {
        if (planIds.isEmpty()) {
            return Map.of();
        }
        return records.findStatuses(planIds).stream()
                .collect(Collectors.toMap(PlanPosition::planId, PlanPosition::status));
    }
}
