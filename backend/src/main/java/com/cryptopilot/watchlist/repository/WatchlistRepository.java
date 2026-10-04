package com.cryptopilot.watchlist.repository;

import com.cryptopilot.watchlist.entity.Watchlist;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to the watchlist rows. Every lookup names the owner, so a row of another Trader is never loaded.
 *
 * <p>Rule: BR-15, BR-16; UC-12.
 */
public interface WatchlistRepository extends Repository<Watchlist, UUID> {

    /** The Trader's row with this key, or empty when there is none or it belongs to someone else. */
    @Transactional(readOnly = true)
    Optional<Watchlist> findByIdAndUserId(UUID watchlistId, UUID userId);

    /** The Trader's rows in list order, then oldest first. */
    @Transactional(readOnly = true)
    List<Watchlist> findByUserIdOrderBySortOrderAscAddedAtAsc(UUID userId);

    @Transactional(readOnly = true)
    boolean existsByUserIdAndPairId(UUID userId, UUID pairId);

    /** The Trader's row for this pair, which an alert hangs on (BR-16). */
    @Transactional(readOnly = true)
    Optional<Watchlist> findByUserIdAndPairId(UUID userId, UUID pairId);

    /** How many pairs the Trader watches: what {@code WATCHLIST_MAX} counts (BR-15, BR-62). */
    @Transactional(readOnly = true)
    long countByUserId(UUID userId);

    /**
     * The position after the Trader's last row, so a new pair is added at the end of the list. Capped at
     * {@code UpdateWatchlistItemRequest.MAX_SORT_ORDER} (10 000): a row stored with a larger position, before that
     * bound existed, would otherwise make {@code max + 1} overflow {@code integer} and refuse every add.
     */
    @Transactional(readOnly = true)
    @Query("select coalesce(case when max(w.sortOrder) >= 10000 then 10000 else max(w.sortOrder) + 1 end, 0)"
            + " from Watchlist w where w.userId = :userId")
    int nextSortOrder(@Param("userId") UUID userId);

    /** All alerts of a watched pair, whatever their status: what MSG26 tells the Trader will be deleted. */
    @Transactional(readOnly = true)
    @Query(value = "select count(*) from alert where watchlist_id = :watchlistId", nativeQuery = true)
    long countAlerts(@Param("watchlistId") UUID watchlistId);

    /** The ACTIVE alerts of one watched pair: the count SCR-12 shows on its row. */
    @Transactional(readOnly = true)
    @Query(
            value = "select count(*) from alert where watchlist_id = :watchlistId and alert_status = 'ACTIVE'",
            nativeQuery = true)
    long countActiveAlerts(@Param("watchlistId") UUID watchlistId);

    /** The ACTIVE alerts of each of the Trader's watched pairs that has any, as (watchlist id, count) pairs. */
    @Transactional(readOnly = true)
    @Query(value = """
                    select watchlist_id, count(*) from alert
                    where user_id = :userId and alert_status = 'ACTIVE'
                    group by watchlist_id""", nativeQuery = true)
    List<Object[]> countActiveAlertsByWatchlist(@Param("userId") UUID userId);

    /** Writes a row. Not transactional here; the unit of work is the calling service's. */
    Watchlist save(Watchlist watchlist);

    /** Sends pending statements, so the unique constraint answers where the caller can read it. */
    void flush();

    /** Removes a row; its alerts go with it through {@code fk_alert_watchlist} (BR-16). */
    void delete(Watchlist watchlist);
}
