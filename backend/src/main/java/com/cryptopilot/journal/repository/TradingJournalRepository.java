package com.cryptopilot.journal.repository;

import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.model.PlanPosition;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The journal records. Only the {@code journal} module reaches this repository, so only it writes
 * {@code trading_journal}.
 *
 * <p>Rule: BR-34, BR-40, BR-62; D-119.
 */
public interface TradingJournalRepository extends Repository<TradingJournal, UUID> {

    /**
     * Inserts a new record, or updates a loaded one, and writes it at once, so a second record of a plan breaks
     * {@code uq_trading_journal_plan} in the caller and not at its commit.
     */
    TradingJournal saveAndFlush(TradingJournal record);

    /** The record of a plan, if the plan filled. */
    @Transactional(readOnly = true)
    Optional<TradingJournal> findByPlanId(UUID planId);

    /** The plans of the user's OPEN SIMULATED records (BR-62); manual records are not positions the system holds. */
    @Transactional(readOnly = true)
    @Query("select j.planId from TradingJournal j where j.userId = :userId"
            + " and j.status = com.cryptopilot.trading.model.enums.PositionStatus.OPEN"
            + " and j.source = com.cryptopilot.journal.model.enums.TradeSource.SIMULATED")
    List<UUID> findOpenPlanIds(UUID userId);

    /** The plan and position status of each record of these plans. */
    @Transactional(readOnly = true)
    @Query("select new com.cryptopilot.journal.model.PlanPosition(j.planId, j.status)"
            + " from TradingJournal j where j.planId in :planIds")
    List<PlanPosition> findStatuses(Collection<UUID> planIds);
}
