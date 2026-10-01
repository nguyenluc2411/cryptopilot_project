package com.cryptopilot.watchlist.dto.response;

import java.util.List;

/**
 * The caller's whole watchlist, in list order, with the plan's limit for the usage shown on SCR-12 and SCR-35. Not
 * paged: the largest plan allows 100 rows.
 *
 * <p>Rule: BR-15, BR-62; SRS 3.4.1; D-75.
 *
 * @param items the rows, smallest position first, then oldest first
 * @param limit the plan's {@code WATCHLIST_MAX}, or {@code null} when unlimited
 */
public record WatchlistResponse(List<WatchlistItemResponse> items, Integer limit) {

    public WatchlistResponse {
        items = List.copyOf(items);
    }
}
