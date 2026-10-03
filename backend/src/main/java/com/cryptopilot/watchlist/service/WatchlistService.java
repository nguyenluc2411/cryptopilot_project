package com.cryptopilot.watchlist.service;

import com.cryptopilot.watchlist.dto.request.AddWatchlistItemRequest;
import com.cryptopilot.watchlist.dto.request.UpdateWatchlistItemRequest;
import com.cryptopilot.watchlist.dto.response.WatchlistItemResponse;
import com.cryptopilot.watchlist.dto.response.WatchlistResponse;
import java.util.UUID;

/**
 * The Trader's watchlist (UC-12). Every method acts on the caller's own rows only; another Trader's row answers as if
 * it did not exist. Implemented by {@link com.cryptopilot.watchlist.service.impl.WatchlistServiceImpl}.
 *
 * <p>Rule: BR-15, BR-16, BR-62; UC-12; D-48, D-63.
 */
public interface WatchlistService {

    WatchlistResponse list(UUID userId);

    /**
     * Adds a listed pair at the end of the list.
     *
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException MSG41 when the pair is unknown or not listed
     * @throws com.cryptopilot.common.exception.BusinessException MSG45 when it is already watched, MSG27 at the limit
     */
    WatchlistItemResponse add(UUID userId, AddWatchlistItemRequest request);

    /** Changes the label, note or position of one of the caller's rows; MSG41 when there is no such row. */
    WatchlistItemResponse update(UUID userId, UUID watchlistId, UpdateWatchlistItemRequest request);

    /**
     * Removes one of the caller's rows and its alerts. A row with alerts needs {@code confirmed}: without it MSG26
     * answers with the number of alerts and nothing is removed. A confirmation that names the number of alerts the
     * Trader saw is checked against the count under the D-63 lock; when an alert was added or removed since, MSG26
     * answers again with the new number and nothing is removed, so a Trader never deletes alerts they were not shown.
     *
     * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 (lost updates;
     * compare-and-set).
     *
     * @param expectedAlertCount the {n} of the MSG26 the Trader confirmed, or {@code null} to confirm whatever the
     *     row holds
     */
    void remove(UUID userId, UUID watchlistId, boolean confirmed, Long expectedAlertCount);
}
