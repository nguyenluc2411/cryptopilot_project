package com.cryptopilot.drawing.repository;

import com.cryptopilot.drawing.entity.ChartDrawing;
import java.util.Collection;
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

    /** The Trader's drawings in one layout, oldest first, which is the order they are painted in. */
    @Transactional(readOnly = true)
    List<ChartDrawing> findByUserIdAndLayoutIdOrderByCreatedAtAscIdAsc(UUID userId, UUID layoutId);

    /** How many drawings one layout holds: what the per-layout limit counts. */
    @Transactional(readOnly = true)
    long countByLayoutId(UUID layoutId);

    /** Drawings per layout as {@code [layoutId, count]} rows; a layout without drawings is left out. */
    @Transactional(readOnly = true)
    @Query("select d.layoutId, count(d) from ChartDrawing d where d.layoutId in :layoutIds group by d.layoutId")
    List<Object[]> countByLayout(@Param("layoutIds") Collection<UUID> layoutIds);

    /** Writes a drawing. Not transactional here; the unit of work is the calling service's. */
    ChartDrawing save(ChartDrawing drawing);

    /** Writes several drawings. Not transactional here; the unit of work is the calling service's. */
    List<ChartDrawing> saveAll(Iterable<ChartDrawing> drawings);

    /** Sends pending statements, so a stale version answers where the caller can read it. */
    void flush();

    void delete(ChartDrawing drawing);

    /** Removes every drawing of the Trader in one layout and answers how many there were. */
    @Modifying
    @Query("delete from ChartDrawing d where d.userId = :userId and d.layoutId = :layoutId")
    int deleteByOwnerAndLayout(@Param("userId") UUID userId, @Param("layoutId") UUID layoutId);
}
