package com.cryptopilot.admin.dto.response;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * One entry of the audit log on SCR-44.
 *
 * <p>Rule: SRS 3.11.9 ("Each entry shows time, actor, action, entity, IP address and the old and new values"); BR-57.
 *
 * @param actor who acted; {@code null} when the system did
 * @param action an {@code AuditAction} name
 * @param entityType an {@code AuditedEntity} name, or {@code null}
 * @param entityId the row acted on, or {@code null} when it has no uuid key (a system setting)
 * @param oldValue the changed fields before, as stored (secrets redacted), or {@code null}
 * @param newValue the changed fields after, or {@code null}
 * @param ipAddress the client address, or {@code null} outside a request
 */
public record AuditLogEntryResponse(
        UUID id,
        Instant recordedAt,
        Actor actor,
        String action,
        String entityType,
        UUID entityId,
        String ipAddress,
        JsonNode oldValue,
        JsonNode newValue) {

    /**
     * The account that acted. Only the id is known when the account no longer exists.
     *
     * @param role {@code TRADER} or {@code ADMIN}, or {@code null} when the account is gone
     */
    public record Actor(UUID id, String email, String displayName, String role) {}
}
