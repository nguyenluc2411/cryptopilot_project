package com.cryptopilot.common.web;

import com.cryptopilot.common.exception.FieldValidationException;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The page a list endpoint was asked for, checked once for every endpoint: a page from 1, 1 to
 * {@link PageResponse#MAX_PAGE_SIZE} items, 20 when absent (CR-04). A value outside that range is a validation error
 * on its own parameter (MSG15), never silently clamped.
 *
 * <p>Rule: CR-04.
 */
public final class Paging {

    /** Items per page when the client names none. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    private Paging() {}

    /**
     * The page asked for, unsorted.
     *
     * @param page from 1, or {@code null} for 1
     * @param pageSize 1 to 100, or {@code null} for {@link #DEFAULT_PAGE_SIZE}
     * @throws FieldValidationException naming {@code page} or {@code pageSize} with MSG15
     */
    public static PageRequest of(Integer page, Integer pageSize) {
        return of(page, pageSize, Sort.unsorted());
    }

    /** The page asked for, in this order; see {@link #of(Integer, Integer)}. */
    public static PageRequest of(Integer page, Integer pageSize, Sort sort) {
        int number = page == null ? 1 : page;
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (number < 1) {
            throw new FieldValidationException("page must be at least 1, was " + number, Map.of("page", "MSG15"));
        }
        if (size < 1 || size > PageResponse.MAX_PAGE_SIZE) {
            throw new FieldValidationException(
                    "pageSize must be between 1 and " + PageResponse.MAX_PAGE_SIZE + ", was " + size,
                    Map.of("pageSize", "MSG15"));
        }
        return PageRequest.of(number - 1, size, sort);
    }
}
