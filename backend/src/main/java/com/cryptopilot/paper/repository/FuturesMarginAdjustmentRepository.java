package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.FuturesMarginAdjustment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * The append-only audit of isolated wallets: written once per change, never updated.
 *
 * <p>Rule: TR-16, TR-17; D-127.
 */
public interface FuturesMarginAdjustmentRepository extends Repository<FuturesMarginAdjustment, UUID> {

    /** The changes of one position, oldest first. */
    List<FuturesMarginAdjustment> findByPositionIdOrderByCreatedAtAscIdAsc(UUID positionId);

    FuturesMarginAdjustment save(FuturesMarginAdjustment adjustment);
}
