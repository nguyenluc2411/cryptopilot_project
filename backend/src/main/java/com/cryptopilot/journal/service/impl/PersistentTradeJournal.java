package com.cryptopilot.journal.service.impl;

import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.model.PlanPosition;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.SimulatedTradeOpening;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.util.Collection;
import java.util.HashSet;
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
 * The journal's side of the plans' fills: it writes the OPEN record inside the fill's transaction and answers what the
 * plans need to know about their positions. The plan's module computes every value; this class stores and reads.
 *
 * <p>Rule: BR-34, BR-62; UC-20; D-63, D-119.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PersistentTradeJournal implements TradeJournal {

    private static final Logger log = LoggerFactory.getLogger(PersistentTradeJournal.class);

    private final TradingJournalRepository records;

    /** Joins the fill's transaction and never starts one: a record without its fill must not commit. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void openSimulatedTrade(SimulatedTradeOpening opening) {
        TradingJournal record = records.saveAndFlush(TradingJournal.openSimulated(opening));
        log.info("BR-34 journal {} opened for plan {} at {}", record.getId(), opening.planId(), opening.entryPrice());
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
