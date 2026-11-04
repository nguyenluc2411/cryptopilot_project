package com.cryptopilot.admin.model;

import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The filters of SCR-44 as the reader applies them; every one present narrows the result, an absent one does not.
 *
 * @param actors when not {@code null}, only entries by these accounts; empty matches nothing
 * @param systemOnly only entries the system recorded ({@code user_id} is null)
 * @param usersOnly only entries an account recorded
 * @param actions when not empty, only these actions
 * @param entityType {@code null} for any
 * @param entityId {@code null} for any
 * @param from first instant included, or {@code null}
 * @param until first instant excluded, or {@code null}
 *     <p>Rule: SRS 3.11.9 (filters: actor, action, entity type, period).
 */
public record AuditLogFilter(
        Set<UUID> actors,
        boolean systemOnly,
        boolean usersOnly,
        Set<AuditAction> actions,
        AuditedEntity entityType,
        UUID entityId,
        Instant from,
        Instant until) {

    public AuditLogFilter {
        actors = actors == null ? null : Set.copyOf(actors);
        actions = actions == null ? Set.of() : Set.copyOf(actions);
    }
}
