package com.cryptopilot.common.web;

import java.util.List;
import java.util.Objects;

/**
 * The response shape of every paged endpoint: the page of items plus the numbers a client needs to
 * render a pager.
 *
 * <p>Pages are numbered from one, the way the query parameter and the user interface count them.
 * The page size is capped so that a caller cannot turn a list endpoint into a full table scan.
 *
 * <p>The arguments are checked in the constructor rather than at each call site, because a page
 * that says it holds twenty items on page zero of a negative total is not a response worth sending.
 *
 * <p>Rule: TECHNICAL_DESIGN section 8 (pagination).
 *
 * @param items the items of this page, in query order; copied, so the response cannot change later
 * @param page the one-based number of this page
 * @param pageSize the maximum number of items a page holds
 * @param total the number of items matching the query across all pages
 * @param <T> the item type
 */
public record PageResponse<T>(List<T> items, int page, int pageSize, long total) {

    /** Largest page a client may ask for. */
    public static final int MAX_PAGE_SIZE = 100;

    public PageResponse {
        Objects.requireNonNull(items, "items must not be null");
        if (page < 1) {
            throw new IllegalArgumentException("page must be at least 1, was " + page);
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE + ", was " + pageSize);
        }
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative, was " + total);
        }
        items = List.copyOf(items);
    }
}
