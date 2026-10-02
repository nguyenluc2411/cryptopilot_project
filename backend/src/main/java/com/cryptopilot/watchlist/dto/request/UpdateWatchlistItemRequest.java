package com.cryptopilot.watchlist.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Edits one row of SCR-12: its label, note or position (SRS 3.4.1). A patch: an absent or {@code null} field is left
 * as it is, and an empty label or note removes it.
 *
 * <p>Rule: SRS 3.4.1; D-75.
 *
 * @param label up to 100 characters; empty to remove
 * @param note up to 1000 characters; empty to remove
 * @param sortOrder the new position, 0 or more; rows with the same position keep the order they were added in
 */
public record UpdateWatchlistItemRequest(
        @Size(max = 100, message = "MSG01") String label,
        @Size(max = 1000, message = "MSG01") String note,
        @Min(value = 0, message = "MSG01") Integer sortOrder) {}
