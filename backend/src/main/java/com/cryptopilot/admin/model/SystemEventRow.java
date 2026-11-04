package com.cryptopilot.admin.model;

import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the system log: a failure, and how often it repeated between its first and last sighting.
 *
 * <p>Rule: UC-52.
 */
public record SystemEventRow(
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
