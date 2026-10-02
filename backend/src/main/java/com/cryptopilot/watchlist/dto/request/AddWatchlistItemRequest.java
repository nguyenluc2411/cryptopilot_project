package com.cryptopilot.watchlist.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * "Add to Watchlist" on SCR-09 to SCR-12 (UC-12). The pair goes to the end of the list.
 *
 * <p>Rule: BR-15; SRS 3.4.1; D-75 (note length).
 *
 * @param pairId the pair, listed on at least one market (BR-07)
 * @param label the group to show it under, up to 100 characters, or {@code null}
 * @param note up to 1000 characters, or {@code null}
 */
public record AddWatchlistItemRequest(
        @NotNull(message = "MSG01") UUID pairId,
        @Size(max = 100, message = "MSG01") String label,
        @Size(max = 1000, message = "MSG01") String note) {}
