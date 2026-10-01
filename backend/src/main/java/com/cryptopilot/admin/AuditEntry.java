package com.cryptopilot.admin;

import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One action to record in the audit trail.
 *
 * <p>{@code actorId} is the account that acted, or {@code null} for the system (a scheduled job or a payment
 * callback). {@code entityId} is {@code null} when the target has no {@code uuid} key: a system setting is keyed by its
 * name, which then belongs in the values. {@code oldValue} and {@code newValue} hold the changed fields before and
 * after; either is {@code null} when there is no before (a creation) or no after. Field names that carry a secret are
 * redacted before the values are stored.
 *
 * <p>Rule: BR-57 (old and new values), NSF-18.
 */
public record AuditEntry(
        UUID actorId,
        AuditAction action,
        AuditedEntity entityType,
        UUID entityId,
        Map<String, Object> oldValue,
        Map<String, Object> newValue) {

    public AuditEntry {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        oldValue = copy(oldValue);
        newValue = copy(newValue);
    }

    // Map.copyOf refuses null values, and a field that was empty before a change is a value worth recording.
    private static Map<String, Object> copy(Map<String, Object> values) {
        return values == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
