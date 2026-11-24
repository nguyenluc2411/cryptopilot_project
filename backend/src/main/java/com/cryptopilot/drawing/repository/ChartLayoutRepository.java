package com.cryptopilot.drawing.repository;

import com.cryptopilot.drawing.entity.ChartLayout;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the chart layouts. Every lookup names the owner, so a layout of another Trader is never loaded.
 *
 * <p>Rule: D-149; T-119.
 */
public interface ChartLayoutRepository extends Repository<ChartLayout, UUID> {

    /** The Trader's layout with this key, or empty when there is none or it belongs to someone else. */
    @Transactional(readOnly = true)
    Optional<ChartLayout> findByIdAndUserId(UUID layoutId, UUID userId);

    /** The Trader's layouts on one pair, by number. */
    @Transactional(readOnly = true)
    List<ChartLayout> findByUserIdAndPairIdOrderBySeqNoAsc(UUID userId, UUID pairId);

    /** The numbers the Trader's layouts on one pair hold, ascending: a new layout takes the smallest one free. */
    @Transactional(readOnly = true)
    @Query("select l.seqNo from ChartLayout l where l.userId = :userId and l.pairId = :pairId order by l.seqNo")
    List<Integer> seqNos(@Param("userId") UUID userId, @Param("pairId") UUID pairId);

    /** Writes a layout. Not transactional here; the unit of work is the calling service's. */
    ChartLayout save(ChartLayout layout);

    /** Sends pending statements, so a stale version answers where the caller can read it. */
    void flush();

    /** Removes the layout; the database removes its drawings with it. */
    void delete(ChartLayout layout);
}
