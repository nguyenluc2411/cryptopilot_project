package com.cryptopilot.admin.entity;

import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One recorded administrative or security-relevant action. Written once and never changed: the entity is
 * {@link Immutable}, so Hibernate issues no update for it, and nothing in the application deletes it. Retention
 * (one year, SRS 3.11.9) is a scheduled delete of NSF-17, not an operation of this class.
 *
 * <p>The values are stored as JSON text already redacted; this class does not look inside them.
 *
 * <p>Rule: BR-57, NSF-18; TECHNICAL_DESIGN section 6 (the audit trail never cascades from an account).
 */
@Getter
@Entity
@Immutable
@Table(name = "audit_log")
@AttributeOverride(name = "id", column = @Column(name = "audit_id", nullable = false, updatable = false))
public class AuditLog extends BaseEntity {

    /** The account that acted, or {@code null} for the system. */
    @Column(name = "user_id", updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_code", nullable = false, updatable = false, length = 64)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", updatable = false, length = 64)
    private AuditedEntity entityType;

    @Column(name = "entity_id", updatable = false)
    private UUID entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", updatable = false)
    private String oldValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", updatable = false)
    private String newValue;

    /** The client address of the request that ran the command, or {@code null} outside a request. */
    @Column(name = "ip_address", updatable = false, length = 45)
    private String ipAddress;

    /** For JPA only. */
    protected AuditLog() {}

    private AuditLog(
            UUID actorId,
            AuditAction action,
            AuditedEntity entityType,
            UUID entityId,
            String oldValue,
            String newValue,
            String ipAddress) {
        this.actorId = actorId;
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.entityId = entityId;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.ipAddress = ipAddress;
    }

    /** Records an action; the values are JSON objects or {@code null}. */
    public static AuditLog record(
            UUID actorId,
            AuditAction action,
            AuditedEntity entityType,
            UUID entityId,
            String oldValueJson,
            String newValueJson,
            String ipAddress) {
        return new AuditLog(actorId, action, entityType, entityId, oldValueJson, newValueJson, ipAddress);
    }
}
