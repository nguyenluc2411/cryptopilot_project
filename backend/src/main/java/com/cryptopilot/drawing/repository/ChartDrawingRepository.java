package com.cryptopilot.drawing.repository;

import com.cryptopilot.drawing.entity.ChartDrawing;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the chart drawings. Every lookup names the owner, so a drawing of another Trader is never loaded.
 *
 * <p>Rule: D-149 (5); T-119.
 */
public interface ChartDrawingRepository extends Repository<ChartDrawing, UUID> {

    /** The Trader's drawing with this key, or empty when there is none or it belongs to someone else. */
    @Transactional(readOnly = true)
    Optional<ChartDrawing> findByIdAndUserId(UUID drawingId, UUID userId);

    /** The Trader's drawings on one pair, oldest first, which is the order they are painted in. */
    @Transactional(readOnly = true)
    List<ChartDrawing> findByUserIdAndPairIdOrderByCreatedAtAscIdAsc(UUID userId, UUID pairId);

    /** How many drawings the Trader has on one pair: what the per-pair limit counts. */
    @Transactional(readOnly = true)
    long countByUserIdAndPairId(UUID userId, UUID pairId);

    /** Writes a drawing. Not transactional here; the unit of work is the calling service's. */
    ChartDrawing save(ChartDrawing drawing);

    /** Sends pending statements, so a stale version answers where the caller can read it. */
    void flush();

    void delete(ChartDrawing drawing);

    /** Removes every drawing of the Trader on one pair and answers how many there were. */
    @Modifying
    @Query("delete from ChartDrawing d where d.userId = :userId and d.pairId = :pairId")
    int deleteByOwnerAndPair(@Param("userId") UUID userId, @Param("pairId") UUID pairId);
}
