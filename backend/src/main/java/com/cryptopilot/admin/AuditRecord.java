package com.cryptopilot.admin;

import com.cryptopilot.admin.model.enums.AuditAction;
import java.time.Instant;
import java.util.UUID;

/**
 * One recorded audit entry, as a detail screen shows it.
 *
 * <p>Rule: BR-57, NSF-18.
 *
 * @param recordedAt when
 * @param actorId who acted, or {@code null} for the system
 * @param action what was done
 * @param oldValue the changed fields before, as JSON text, or {@code null}
 * @param newValue the changed fields after, as JSON text, or {@code null}
 * @param ipAddress the client address, or {@code null} outside a request
 */
public record AuditRecord(
        Instant recordedAt, UUID actorId, AuditAction action, String oldValue, String newValue, String ipAddress) {}
