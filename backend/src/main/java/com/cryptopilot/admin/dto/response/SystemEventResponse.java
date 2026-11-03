package com.cryptopilot.admin.dto.response;

import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the system log on SCR-44: a failure and how often it repeated.
 *
 * <p>Rule: UC-46.
 *
 * @param operation a request's method and path, a job's class and method, or a client's class and method
 * @param errorType the class of what was thrown
 * @param message its message, or {@code null}
 * @param reference the reference a caller was answered with, for an unexpected error of a request
 * @param occurrences how many times it was seen between {@code firstSeenAt} and {@code lastSeenAt}
 */
public record SystemEventResponse(
        UUID id,
        SystemEventCategory category,
        SystemEventSource source,
        String operation,
        String errorType,
        String message,
        String reference,
        int occurrences,
        Instant firstSeenAt,
        Instant lastSeenAt) {}
