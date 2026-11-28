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

    /** The plan and position status of each record of these plans. */
    @Transactional(readOnly = true)
    @Query("select new com.cryptopilot.journal.model.PlanPosition(j.planId, j.status)"
            + " from TradingJournal j where j.planId in :planIds")
    List<PlanPosition> findStatuses(Collection<UUID> planIds);

    /** The record if it is an OPEN SIMULATED one, a plan's on the demo account included (RF-33). */
    @Transactional(readOnly = true)
    @Query("select j from TradingJournal j where j.id = :id"
            + " and j.status = com.cryptopilot.trading.model.enums.PositionStatus.OPEN"
            + " and j.source = com.cryptopilot.journal.model.enums.TradeSource.SIMULATED")
    Optional<TradingJournal> findOpenSimulated(UUID id);

    /**
     * The user's OPEN SIMULATED records, oldest entry first: the positions whose current risk BR-29 counts and whose
     * slots BR-62 keeps, those of plans carried out on the demo account included (RF-33).
     */
    @Transactional(readOnly = true)
    @Query("select j from TradingJournal j where j.userId = :userId"
            + " and j.status = com.cryptopilot.trading.model.enums.PositionStatus.OPEN"
            + " and j.source = com.cryptopilot.journal.model.enums.TradeSource.SIMULATED"
            + " order by j.entryTime, j.id")
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

    /** The columns a manual trade is written with, by {@link #insertManual} and {@link #updateManual}. */
    String MANUAL_VALUES = "cast(:pairId as uuid), cast(:strategyId as uuid), :market, :direction,"
            + " cast(:leverage as integer), :entryPrice, cast(:stopLoss as numeric), cast(:takeProfit as numeric),"
            + " :quantity, :entryTime, cast(:exitPrice as numeric), cast(:exitTime as timestamptz),"
            + " cast(:closeReason as varchar), :feeAmount, cast(:fundingFeeAmount as numeric),"
            + " cast(:realizedPnl as numeric), :status, cast(:setupDescription as text),"
            + " cast(:lessonsLearned as text), cast(:mistakes as text)";

    /** The column list matching {@link #MANUAL_VALUES}. */
    String MANUAL_COLUMNS = "pair_id, strategy_id, market_type, direction, leverage, entry_price, stop_loss_price,"
            + " take_profit_price, quantity, entry_time, exit_price, exit_time, close_reason, fee_amount,"
            + " funding_fee_amount, realized_pnl, trade_status, setup_description, lessons_learned, mistakes";

    /**
     * Inserts a MANUAL record with every value the Trader entered and the amounts derived from them (BR-41). Native,
     * because the entity maps the exit columns read-only: only the close writes them for a simulated trade.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "insert into trading_journal (journal_id, user_id, source, " + MANUAL_COLUMNS
                    + ", created_at, updated_at, version) values (:id, :userId, 'MANUAL', " + MANUAL_VALUES
                    + ", :now, :now, 0)",
            nativeQuery = true)
    int insertManual(
            UUID id,
            UUID userId,
            UUID pairId,
            UUID strategyId,
            String market,
            String direction,
            Integer leverage,
            BigDecimal entryPrice,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            BigDecimal quantity,
            Instant entryTime,
            BigDecimal exitPrice,
            Instant exitTime,
            String closeReason,
            BigDecimal feeAmount,
            BigDecimal fundingFeeAmount,
            BigDecimal realizedPnl,
            String status,
            String setupDescription,
            String lessonsLearned,
            String mistakes,
            Instant now);

    /**
     * Replaces every entered value of one of the user's MANUAL records; its chart image stays. A SIMULATED record is
     * never matched (BR-40).
     *
     * @return 1 when the record is the user's manual one, otherwise 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "update trading_journal set (" + MANUAL_COLUMNS + ", updated_at, version) = (" + MANUAL_VALUES
                    + ", :now, version + 1) where journal_id = :id and user_id = :userId and source = 'MANUAL'",
            nativeQuery = true)
    int updateManual(
            UUID id,
            UUID userId,
            UUID pairId,
            UUID strategyId,
            String market,
            String direction,
            Integer leverage,
            BigDecimal entryPrice,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            BigDecimal quantity,
            Instant entryTime,
            BigDecimal exitPrice,
            Instant exitTime,
            String closeReason,
            BigDecimal feeAmount,
            BigDecimal fundingFeeAmount,
            BigDecimal realizedPnl,
            String status,
            String setupDescription,
            String lessonsLearned,
            String mistakes,
            Instant now);

    /**
     * Deletes one of the user's MANUAL records, permanently (SRS 4: manual trades are not soft deleted); a SIMULATED
     * record is never matched (BR-40).
     *
     * @return 1 when it was deleted, otherwise 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "delete from trading_journal where journal_id = :id and user_id = :userId and source = 'MANUAL'",
            nativeQuery = true)
    int deleteManual(UUID id, UUID userId);

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

    // ------------------------------------------------------------------------------------------
    // Records of paper fills (RF-35)
    // ------------------------------------------------------------------------------------------

    /** The record a Spot buy order opened, or empty. */
    @Transactional(readOnly = true)
    Optional<TradingJournal> findByPaperOrderId(UUID paperOrderId);

    /** The record that follows a paper Futures position, or empty. */
    @Transactional(readOnly = true)
    Optional<TradingJournal> findByPaperPositionId(UUID paperPositionId);

    /** Whether the plan has a record already: a plan results in at most one trade. */
    @Transactional(readOnly = true)
    boolean existsByPlanId(UUID planId);

    /** The Trader's OPEN Spot records from paper buys of the pair, oldest entry first: what a sale closes. */
    @Transactional(readOnly = true)
    @Query("select j from TradingJournal j where j.userId = :userId and j.pairId = :pairId"
            + " and j.status = com.cryptopilot.trading.model.enums.PositionStatus.OPEN"
            + " and j.paperOrderId is not null order by j.entryTime, j.id")
    List<TradingJournal> findOpenPaperSpot(UUID userId, UUID pairId);

    /** A later fill of the same Spot buy order: the quantity grows and the entry becomes their average. */
    @Modifying(flushAutomatically = true)
    @Query(
            value = "update trading_journal set"
                    + " entry_price = (entry_price * quantity + :price * :quantity) / (quantity + :quantity),"
                    + " quantity = quantity + :quantity,"
                    + " entry_fee_amount = coalesce(entry_fee_amount, 0) + :fee,"
                    + " updated_at = :now, version = version + 1"
                    + " where journal_id = :id and trade_status = 'OPEN'",
            nativeQuery = true)
    int addToEntry(UUID id, BigDecimal price, BigDecimal quantity, BigDecimal fee, Instant now);

    /** Part of a sale taken by an OPEN Spot record: what it closed, at what notional, realised and paid. */
    @Modifying(flushAutomatically = true)
    @Query(
            value = "update trading_journal set exit_quantity = exit_quantity + :quantity,"
                    + " exit_notional = exit_notional + :notional,"
                    + " exit_realized_pnl = exit_realized_pnl + :realized,"
                    + " exit_fee_amount = exit_fee_amount + :fee,"
                    + " updated_at = :now, version = version + 1"
                    + " where journal_id = :id and trade_status = 'OPEN'",
            nativeQuery = true)
    int addExit(UUID id, BigDecimal quantity, BigDecimal notional, BigDecimal realized, BigDecimal fee, Instant now);

    /** Closes a Spot record sold out: its exit is the average of the sales, its fees the entry's and the sales'. */
    @Modifying(flushAutomatically = true)
    @Query(
            value = "update trading_journal set trade_status = 'CLOSED',"
                    + " exit_price = round(exit_notional / exit_quantity, 12), exit_time = :exitTime,"
                    + " close_reason = :reason, realized_pnl = exit_realized_pnl,"
                    + " fee_amount = coalesce(entry_fee_amount, 0) + exit_fee_amount,"
                    + " updated_at = :now, version = version + 1"
                    + " where journal_id = :id and trade_status = 'OPEN' and exit_quantity = quantity",
            nativeQuery = true)
    int closeSoldOut(UUID id, Instant exitTime, String reason, Instant now);

    /** An OPEN Futures record follows its position: the largest quantity, the average entry and the leverage. */
    @Modifying(flushAutomatically = true)
    @Query(
            value = "update trading_journal set quantity = :quantity, entry_price = :entryPrice,"
                    + " leverage = :leverage, updated_at = :now, version = version + 1"
                    + " where journal_id = :id and trade_status = 'OPEN'",
            nativeQuery = true)
    int followPosition(UUID id, BigDecimal quantity, BigDecimal entryPrice, int leverage, Instant now);

    /** Closes a Futures record with its position's own exit figures. */
    @Modifying(flushAutomatically = true)
    @Query(
            value = "update trading_journal set trade_status = 'CLOSED', quantity = :quantity,"
                    + " entry_price = :entryPrice, exit_price = :exitPrice, exit_time = :exitTime,"
                    + " close_reason = :reason, realized_pnl = :realizedPnl, fee_amount = :feeAmount,"
                    + " funding_fee_amount = :fundingFeeAmount, updated_at = :now, version = version + 1"
                    + " where journal_id = :id and trade_status = 'OPEN'",
            nativeQuery = true)
    int closePosition(
            UUID id,
            BigDecimal quantity,
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            Instant exitTime,
            String reason,
            BigDecimal realizedPnl,
            BigDecimal feeAmount,
            BigDecimal fundingFeeAmount,
            Instant now);
}
