package com.cryptopilot.trading.repository;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.PlanStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The trading plans. {@link TradingPlan} is the aggregate root, so its warnings are saved and deleted through it and
 * have no repository of their own.
 *
 * <p>Rule: BR-31, BR-32; NSF-07; TECHNICAL_DESIGN 5.5; D-23.
 */
public interface TradingPlanRepository extends Repository<TradingPlan, UUID> {

    /** Inserts a new plan or updates a loaded one, with its warnings. */
    TradingPlan save(TradingPlan plan);

    @Transactional(readOnly = true)
    Optional<TradingPlan> findById(UUID id);

    /** The plan, when it belongs to the user; another user's plan is not found (404, never 403). */
    Optional<TradingPlan> findByIdAndUserId(UUID id, UUID userId);

    /**
     * The user's plan, row-locked until the transaction ends ({@code select ... for update}). A cancel reads it this way,
     * so it waits for a fill in progress and then sees the fill's status (NSF-07, TECHNICAL_DESIGN 5.5).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from TradingPlan p where p.id = :id and p.userId = :userId")
    Optional<TradingPlan> findForUpdate(UUID id, UUID userId);

    /** The user's ACTIVE plans, the plan half of {@code ACTIVE_PLAN_MAX} (BR-62). */
    @Transactional(readOnly = true)
    @Query("select count(p) from TradingPlan p where p.userId = :userId"
            + " and p.status = com.cryptopilot.trading.model.enums.PlanStatus.ACTIVE")
    long countActive(UUID userId);

    /** The risk amount of the user's ACTIVE plans other than {@code excluded}, in USDT (BR-29 TOTAL_OPEN_RISK). */
    @Transactional(readOnly = true)
    @Query("select coalesce(sum(p.riskAmount), 0) from TradingPlan p where p.userId = :userId"
            + " and p.status = com.cryptopilot.trading.model.enums.PlanStatus.ACTIVE and p.id <> :excluded")
    BigDecimal activeRiskExcluding(UUID userId, UUID excluded);

    /**
     * A page of the user's plans in the statuses and markets, created in {@code [from, to)}, newest first; the pair
     * filter applies unless {@code anyPair}. No parameter is null, so PostgreSQL can type every one. The warnings are
     * not loaded.
     */
    @Transactional(readOnly = true)
    @Query(
            value = "select p from TradingPlan p where p.userId = :userId and p.status in :statuses"
                    + " and p.market in :markets and (:anyPair = true or p.pairId = :pairId)"
                    + " and p.createdAt >= :from and p.createdAt < :to order by p.createdAt desc, p.id desc",
            countQuery = "select count(p) from TradingPlan p where p.userId = :userId and p.status in :statuses"
                    + " and p.market in :markets and (:anyPair = true or p.pairId = :pairId)"
                    + " and p.createdAt >= :from and p.createdAt < :to")
    Page<TradingPlan> search(
            UUID userId,
            Collection<PlanStatus> statuses,
            Collection<MarketType> markets,
            boolean anyPair,
            UUID pairId,
            Instant from,
            Instant to,
            Pageable pageable);

    /**
     * Compare-and-set ACTIVE to EXECUTED, with the fill time and price: exactly one of two racing writers sees {@code 1}. The version is raised, so a
     * cancel that loaded the plan before this update fails its optimistic lock instead of overwriting the fill.
     *
     * @return 1 when the plan was ACTIVE and is now EXECUTED, otherwise 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update TradingPlan p set p.status = com.cryptopilot.trading.model.enums.PlanStatus.EXECUTED,"
            + " p.executedAt = :at, p.fillPrice = :price, p.updatedAt = :now, p.version = p.version + 1"
            + " where p.id = :id and p.status = com.cryptopilot.trading.model.enums.PlanStatus.ACTIVE")
    int executeIfActive(UUID id, Instant at, BigDecimal price, Instant now);

    /** The entries of every ACTIVE LIMIT plan, for the matching books. */
    @Transactional(readOnly = true)
    @Query("select new com.cryptopilot.trading.model.TrackedEntry(p.id, p.market, p.pairId, p.direction, p.entryPrice,"
            + " p.activatedAt)"
            + " from TradingPlan p where p.status = com.cryptopilot.trading.model.enums.PlanStatus.ACTIVE"
            + " and p.entryType = com.cryptopilot.trading.model.enums.EntryType.LIMIT")
    List<TrackedEntry> findActiveLimitEntries();
}
