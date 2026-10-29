package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.PositionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * The paper Futures positions. Every lookup a Trader causes names the Trader.
 *
 * <p>Rule: TR-14, TR-16; D-127.
 */
public interface FuturesPositionRepository extends Repository<FuturesPosition, UUID> {

    /** The Trader's positions in a status, e.g. the open ones of every pair. */
    List<FuturesPosition> findByUserIdAndPositionStatus(UUID userId, PositionStatus status);

    /** The Trader's positions of one pair in a status: one per position side when open. */
    List<FuturesPosition> findByUserIdAndPairIdAndPositionStatus(UUID userId, UUID pairId, PositionStatus status);

    /** One position. */
    Optional<FuturesPosition> findById(UUID positionId);

    /** Every position of every Trader in a status and margin type: the open isolated ones the engine watches. */
    List<FuturesPosition> findByPositionStatusAndMarginType(PositionStatus status, MarginType marginType);

    /** Every position of every Trader in a status: the open ones whose funding is settled. */
    List<FuturesPosition> findByPositionStatus(PositionStatus status);

    /** Every position closed at or after an instant: a settlement before its close is still owed (TR-24). */
    List<FuturesPosition> findByClosedAtGreaterThanEqual(Instant closedAt);

    FuturesPosition save(FuturesPosition position);
}
