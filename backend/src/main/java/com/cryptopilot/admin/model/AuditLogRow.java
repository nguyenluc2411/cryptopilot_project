package com.cryptopilot.admin.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One stored audit entry as SCR-44 lists it; the values are the stored JSON text, already redacted.
 *
 * <p>Rule: SRS 3.11.9; BR-57.
 */
public record AuditLogRow(
        UUID id,
        Instant recordedAt,
        UUID actorId,
        String action,
        String entityType,
        UUID entityId,
        String oldValue,
        String newValue,
        String ipAddress) {}
