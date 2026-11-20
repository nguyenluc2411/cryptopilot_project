package com.cryptopilot.paper.repository;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.entity.PaperFill;
import com.cryptopilot.paper.model.enums.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the fills of paper orders: written once per execution, read as the trade history.
 *
 * <p>Rule: TR-02.
 */
public interface PaperFillRepository extends Repository<PaperFill, UUID> {

    /**
     * One page of an account's fills on one market traded in {@code [from, to)}, newest first. No parameter is
     * null.
     */
    @Transactional(readOnly = true)
    @Query(
            value = "select f from PaperFill f where f.userId = :userId and f.marketType = :marketType"
                    + " and f.tradedAt >= :from and f.tradedAt < :to order by f.tradedAt desc, f.id desc",
            countQuery = "select count(f) from PaperFill f where f.userId = :userId and f.marketType = :marketType"
                    + " and f.tradedAt >= :from and f.tradedAt < :to")
    Page<PaperFill> search(UUID userId, MarketType marketType, Instant from, Instant to, Pageable pageable);

    /** As {@link #search}, for one pair. */
    @Transactional(readOnly = true)
    @Query(
            value = "select f from PaperFill f where f.userId = :userId and f.pairId = :pairId"
                    + " and f.marketType = :marketType and f.tradedAt >= :from and f.tradedAt < :to"
                    + " order by f.tradedAt desc, f.id desc",
            countQuery = "select count(f) from PaperFill f where f.userId = :userId and f.pairId = :pairId"
                    + " and f.marketType = :marketType and f.tradedAt >= :from and f.tradedAt < :to")
    Page<PaperFill> searchPair(
            UUID userId, UUID pairId, MarketType marketType, Instant from, Instant to, Pageable pageable);

    /**
     * The size of a Futures position at an instant: what its fills before it opened, less what they closed (plan 9.4,
     * the quantity a funding settlement is charged on). Every fill of a position on its opening side opened it. A fill a
     * replay applied counts at the time of the price it took, not when it was applied (D-159).
     */
    @Query("select coalesce(sum(case when f.side = :openingSide then f.fillQuantity else -f.fillQuantity end), 0)"
            + " from PaperFill f where f.positionId = :positionId"
            + " and coalesce(f.sourceTime, f.tradedAt) < :at")
    BigDecimal sizeBefore(UUID positionId, OrderSide openingSide, Instant at);

    /**
     * When a Futures position opened on the exchange: the earliest of its fills, a replayed one counting at the time of
     * the price it took (D-159). Empty for a position with no fill.
     */
    @Query("select min(coalesce(f.sourceTime, f.tradedAt)) from PaperFill f where f.positionId = :positionId")
    Optional<Instant> firstFillAt(UUID positionId);

    /** Writes a fill. Not transactional here; the unit of work is the calling service's. */
    PaperFill save(PaperFill fill);
}
