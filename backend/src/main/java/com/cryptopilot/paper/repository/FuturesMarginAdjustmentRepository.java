package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.FuturesMarginAdjustment;
import com.cryptopilot.paper.model.enums.MarginAdjustmentType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * The append-only audit of isolated wallets: written once per change, never updated.
 *
 * <p>Rule: TR-16, TR-17; D-127.
 */
public interface FuturesMarginAdjustmentRepository extends Repository<FuturesMarginAdjustment, UUID> {

    /** The changes of one position, oldest first. */
    List<FuturesMarginAdjustment> findByPositionIdOrderByCreatedAtAscIdAsc(UUID positionId);

    /**
     * The changes of these types the Trader's positions on one pair had in {@code [from, to)}, newest first. No
     * parameter is null.
     */
    @Query("select a from FuturesMarginAdjustment a where a.userId = :userId and a.pairId = :pairId"
            + " and a.adjustmentType in :types and a.createdAt >= :from and a.createdAt < :to"
            + " order by a.createdAt desc, a.id desc")
    List<FuturesMarginAdjustment> search(
            UUID userId,
            UUID pairId,
            Collection<MarginAdjustmentType> types,
            Instant from,
            Instant to,
            Pageable pageable);

    FuturesMarginAdjustment save(FuturesMarginAdjustment adjustment);
}
