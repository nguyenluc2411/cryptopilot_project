package com.cryptopilot.journal.service.impl;

import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.model.PlanPosition;
import com.cryptopilot.journal.repository.LevelChangeLog;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.PositionLevels;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.SimulatedFillLearning;
import com.cryptopilot.trading.SimulatedLevelChange;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * their positions, changes their levels with a compare-and-set and keeps the history of those changes (TP-E). The
 * learning data of a fill goes into the OPEN record, so the unique plan key that refuses a replayed fill refuses its
 * learning data too; the extremes of an open position only ever widen. The plan's module computes every value; this
 * class stores and reads.
 *
 * <p>Rule: BR-34, BR-35, BR-62, BR-70; UC-20, UC-21; D-63, D-97, D-111, D-119, D-124.
 *
 * <p>Reference: Helland, P. (2012). Idempotence is not a medical condition. <i>ACM Queue</i>, 10(4) (a repeated
 * message changes nothing).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PersistentTradeJournal implements TradeJournal {

    private static final Logger log = LoggerFactory.getLogger(PersistentTradeJournal.class);

    private final TradingJournalRepository records;
    private final LevelChangeLog levelChanges;
    private final Clock clock;

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

    /** Joins the change's transaction and never starts one: the change and its history row commit together. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OpenTrade> changeSimulatedLevels(SimulatedLevelChange change) {
        boolean changed = records.changeLevelsIfOpen(
                        change.positionId(),
                        change.version(),
                        change.newStopLoss(),
                        change.newTakeProfit(),
                        change.widensStop(),
                        clock.instant())
                == 1;
        if (!changed) {
            return Optional.empty();
        }
        levelChanges.append(change.history(), clock.instant());
        log.info(
                "D-124 journal {} levels changed: stop loss {} -> {}, take profit {} -> {}{}",
                change.positionId(),
                change.oldStopLoss(),
                change.newStopLoss(),
                change.oldTakeProfit(),
                change.newTakeProfit(),
                change.widensStop() ? ", stop widened" : "");
        return records.findOpenSimulated(change.positionId()).map(TradingJournal::toOpenTrade);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpenTrade> openSimulatedTrades(UUID userId) {
        return records.findOpenSimulatedOf(userId).stream()
                .map(TradingJournal::toOpenTrade)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PositionLevels> levelsOf(UUID planId) {
        return records.findByPlanId(planId).map(record -> record.toLevels(levelChanges.of(record.getId())));
    }

    /** Joins the fill's transaction and never starts one: the fill and its learning data commit together. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> recordFillLearning(UUID planId, SimulatedFillLearning learning) {
        return records.findByPlanId(planId).map(record -> {
            record.recordFillLearning(learning);
            return record.getId();
        });
    }
}
