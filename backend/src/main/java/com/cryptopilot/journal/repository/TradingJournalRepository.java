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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
     * The monotonic merge of a stored most adverse and most favourable price with new ones, by the row's direction:
     * PostgreSQL's {@code least} and {@code greatest} skip a null, so a missing new value keeps the stored one and a
     * missing stored value takes the new one (BR-70).
     */
    String WIDEN_EXCURSIONS = "max_adverse_price = case direction when 'LONG'"
            + " then least(max_adverse_price, cast(:adverse as numeric))"
            + " else greatest(max_adverse_price, cast(:adverse as numeric)) end,"
            + " max_favorable_price = case direction when 'LONG'"
            + " then greatest(max_favorable_price, cast(:favorable as numeric))"
            + " else least(max_favorable_price, cast(:favorable as numeric)) end";

    /** The rows of {@link #search}, for its page and its count; a result only ever matches a CLOSED row (BR-42). */
    String SEARCHED_ROWS = " from trading_journal where user_id = :userId and trade_status in (:statuses)"
            + " and market_type in (:markets) and source in (:sources)"
            + " and entry_time >= :from and entry_time < :to"
            + " and (:anyPair = true or pair_id = :pairId)"
            + " and (:anyStrategy = true or strategy_id = :strategyId)"
            + " and (:anyResult = true or (trade_status = 'CLOSED' and sign(" + PerformanceQueryRepository.NET_PNL
            + ") = :resultSign))";

    /**
     * Inserts a new record, or updates a loaded one, and writes it at once, so a second record of a plan breaks
     * {@code uq_trading_journal_plan} in the caller and not at its commit.
     */
    TradingJournal saveAndFlush(TradingJournal record);

    /**
     * A page of the user's records entered in {@code [from, to)}, the latest entry first (SCR-20); the pair, strategy
     * and result filters apply unless their {@code any} flag is set. No parameter is null, so PostgreSQL can type every
     * one. Native, because the result reads the one SQL expression of the net P/L.
     *
     * @param resultSign the {@code sign} of the net P/L a row must have
     */
    @Transactional(readOnly = true)
    @Query(
            value = "select *" + SEARCHED_ROWS + " order by entry_time desc, journal_id desc",
            countQuery = "select count(*)" + SEARCHED_ROWS,
            nativeQuery = true)
    Page<TradingJournal> search(
            UUID userId,
            Collection<String> statuses,
            Collection<String> markets,
            Collection<String> sources,
            Instant from,
            Instant to,
            boolean anyPair,
            UUID pairId,
            boolean anyStrategy,
            UUID strategyId,
            boolean anyResult,
            int resultSign,
            Pageable page);

    /** The user's record, or nothing when it is another Trader's, so a foreign id is not confirmed (SCR-21). */
    @Transactional(readOnly = true)
    Optional<TradingJournal> findByIdAndUserId(UUID id, UUID userId);

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
     * are a PostgreSQL array literal, or {@code null} for none; the extremes are merged as {@link #WIDEN_EXCURSIONS}
     * does, or kept when {@code null}.
     *
     * @return 1 when the record was OPEN at that version and is now CLOSED, otherwise 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "update trading_journal set trade_status = 'CLOSED', exit_price = :exitPrice,"
                    + " exit_time = :exitTime, close_reason = :reason, realized_pnl = :realizedPnl,"
                    + " fee_amount = :feeAmount, funding_fee_amount = :fundingFeeAmount,"
                    + " funding_incomplete = :fundingIncomplete,"
                    + " missing_funding_times = cast(:missingFundingTimes as timestamptz[]), "
                    + WIDEN_EXCURSIONS
                    + ", updated_at = :now, version = version + 1"
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
            BigDecimal adverse,
            BigDecimal favorable,
            Instant now);

    /**
     * Widens the extremes of an OPEN SIMULATED record, see {@link #WIDEN_EXCURSIONS}. The version and the levels stay,
     * so a close or a change of the levels racing it is never refused; nor does {@code updated_at} move, as nothing the
     * Trader changed did.
     *
     * @return 1 when the record was OPEN, otherwise 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "update trading_journal set " + WIDEN_EXCURSIONS
                    + " where journal_id = :id and trade_status = 'OPEN' and source = 'SIMULATED'",
            nativeQuery = true)
    int widenExcursionIfOpen(UUID id, BigDecimal adverse, BigDecimal favorable);

    /** The user's OPEN SIMULATED records, oldest entry first: the positions whose current risk BR-29 counts. */
    @Transactional(readOnly = true)
    @Query("select j from TradingJournal j where j.userId = :userId"
            + " and j.status = com.cryptopilot.trading.model.enums.PositionStatus.OPEN"
            + " and j.source = com.cryptopilot.journal.model.enums.TradeSource.SIMULATED order by j.entryTime, j.id")
    List<TradingJournal> findOpenSimulatedOf(UUID userId);

    /**
     * Compare-and-set of the current levels of an OPEN record at one version: of a change and an exit that read the
     * same version, exactly one changes the row. {@code stop_widened} only ever turns on; the count goes up by one.
     *
     * @return 1 when the record was OPEN at that version and its levels are now the new ones, otherwise 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "update trading_journal set stop_loss_price = :stopLoss, take_profit_price = :takeProfit,"
                    + " stop_widened = stop_widened or :widensStop, level_change_count = level_change_count + 1,"
                    + " updated_at = :now, version = version + 1"
                    + " where journal_id = :id and trade_status = 'OPEN' and source = 'SIMULATED'"
                    + " and version = :version",
            nativeQuery = true)
    int changeLevelsIfOpen(
            UUID id, long version, BigDecimal stopLoss, BigDecimal takeProfit, boolean widensStop, Instant now);

    /**
     * Replaces the review of one of the user's records: strategy, texts and chart image. Only these columns change:
     * neither the execution facts (BR-40) nor the version, which the close and the level change of an open position
     * compare and set, so a review never makes the matching engine's exit miss.
     *
     * @return 1 when the record is the user's, otherwise 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "update trading_journal set strategy_id = cast(:strategyId as uuid),"
                    + " setup_description = cast(:setupDescription as text),"
                    + " lessons_learned = cast(:lessonsLearned as text), mistakes = cast(:mistakes as text),"
                    + " chart_image_url = cast(:chartImageUrl as varchar),"
                    + " chart_image_public_id = cast(:chartImagePublicId as varchar), updated_at = :now"
                    + " where journal_id = :id and user_id = :userId",
            nativeQuery = true)
    int review(
            UUID id,
            UUID userId,
            UUID strategyId,
            String setupDescription,
            String lessonsLearned,
            String mistakes,
            String chartImageUrl,
            String chartImagePublicId,
            Instant now);

    /** The user's CLOSED SIMULATED records, the latest exit first (BR-70). */
    @Transactional(readOnly = true)
    @Query("select j from TradingJournal j where j.userId = :userId"
            + " and j.status = com.cryptopilot.trading.model.enums.PositionStatus.CLOSED"
            + " and j.source = com.cryptopilot.journal.model.enums.TradeSource.SIMULATED"
            + " order by j.exitTime desc, j.id desc")
    List<TradingJournal> findLatestClosedSimulated(UUID userId, Limit limit);

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
