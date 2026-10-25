package com.cryptopilot.journal.repository;

import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.model.PlanPosition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The journal records. Only the {@code journal} module reaches this repository, so only it writes
 * {@code trading_journal}.
 *
 * <p>Rule: BR-34, BR-35, BR-37, BR-40, BR-62; D-97 and its note of 2026-10-24; D-119.
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

    /** Every OPEN SIMULATED record, oldest entry first: the positions the matching engine tracks. */
    @Transactional(readOnly = true)
    @Query("select j from TradingJournal j"
            + " where j.status = com.cryptopilot.trading.model.enums.PositionStatus.OPEN"
            + " and j.source = com.cryptopilot.journal.model.enums.TradeSource.SIMULATED order by j.entryTime, j.id")
    List<TradingJournal> findOpenSimulated();

    /** The record if it is an OPEN SIMULATED one. */
    @Transactional(readOnly = true)
    @Query("select j from TradingJournal j where j.id = :id"
            + " and j.status = com.cryptopilot.trading.model.enums.PositionStatus.OPEN"
            + " and j.source = com.cryptopilot.journal.model.enums.TradeSource.SIMULATED")
    Optional<TradingJournal> findOpenSimulated(UUID id);

    /** The record of the plan if it is an OPEN SIMULATED one. */
    @Transactional(readOnly = true)
    @Query("select j from TradingJournal j where j.planId = :planId"
            + " and j.status = com.cryptopilot.trading.model.enums.PositionStatus.OPEN"
            + " and j.source = com.cryptopilot.journal.model.enums.TradeSource.SIMULATED")
    Optional<TradingJournal> findOpenSimulatedOfPlan(UUID planId);

    /**
     * Compare-and-set OPEN to CLOSED at one version, with the exit and the amounts: of two writers that read the same
     * version, exactly one changes the row; the other, and a replay of the same exit, see 0. The missing funding times
     * are a PostgreSQL array literal, or {@code null} for none.
     *
     * @return 1 when the record was OPEN at that version and is now CLOSED, otherwise 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "update trading_journal set trade_status = 'CLOSED', exit_price = :exitPrice,"
                    + " exit_time = :exitTime, close_reason = :reason, realized_pnl = :realizedPnl,"
                    + " fee_amount = :feeAmount, funding_fee_amount = :fundingFeeAmount,"
                    + " funding_incomplete = :fundingIncomplete,"
                    + " missing_funding_times = cast(:missingFundingTimes as timestamptz[]),"
                    + " updated_at = :now, version = version + 1"
                    + " where journal_id = :id and trade_status = 'OPEN' and source = 'SIMULATED'"
                    + " and version = :version",
            nativeQuery = true)
    int closeIfOpen(
            UUID id,
            long version,
            BigDecimal exitPrice,
            Instant exitTime,
            String reason,
            BigDecimal realizedPnl,
            BigDecimal feeAmount,
            BigDecimal fundingFeeAmount,
            boolean fundingIncomplete,
            String missingFundingTimes,
            Instant now);

    /** CLOSED records whose funding may still miss a settlement, the oldest exits first. */
    @Transactional(readOnly = true)
    @Query("select j from TradingJournal j where j.fundingIncomplete = true"
            + " and j.status = com.cryptopilot.trading.model.enums.PositionStatus.CLOSED order by j.exitTime, j.id")
    List<TradingJournal> findIncompleteFunding(Limit limit);

    /**
     * Replaces the funding of a CLOSED record still marked incomplete with a recomputed one; a record already
     * corrected by another run is left as it is.
     *
     * @return 1 when the record was changed, otherwise 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "update trading_journal set funding_fee_amount = :amount, funding_incomplete = :incomplete,"
                    + " missing_funding_times = cast(:missingFundingTimes as timestamptz[]),"
                    + " updated_at = :now, version = version + 1"
                    + " where journal_id = :id and trade_status = 'CLOSED' and funding_incomplete",
            nativeQuery = true)
    int correctFunding(UUID id, BigDecimal amount, boolean incomplete, String missingFundingTimes, Instant now);
}
