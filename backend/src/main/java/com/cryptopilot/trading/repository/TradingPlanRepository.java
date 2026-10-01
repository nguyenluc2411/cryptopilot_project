package com.cryptopilot.trading.repository;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.model.enums.PlanStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The trading plans. {@link TradingPlan} is the aggregate root, so its warnings are saved and deleted through it and
 * have no repository of their own. The compare-and-set status update of TECHNICAL_DESIGN 5.5 comes with the matching engine (T-042).
 *
 * <p>Rule: BR-31, BR-32; TECHNICAL_DESIGN 5.5; D-23.
 */
public interface TradingPlanRepository extends Repository<TradingPlan, UUID> {

    /** Inserts a new plan or updates a loaded one, with its warnings. */
    TradingPlan save(TradingPlan plan);

    @Transactional(readOnly = true)
    Optional<TradingPlan> findById(UUID id);

    /** The plan, when it belongs to the user; another user's plan is not found (404, never 403). */
    Optional<TradingPlan> findByIdAndUserId(UUID id, UUID userId);

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
}
