package com.cryptopilot.admin.repository;

import com.cryptopilot.admin.AuditRecord;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code audit_log} for the detail screens. The entity is write-only by design (D-74), so the reads go through
 * here, with the JSON columns as text.
 *
 * <p>Rule: BR-57; D-74.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AuditLogReader {

    private final JdbcClient sql;

    /** The latest entries about one object, newest first. */
    public List<AuditRecord> latest(AuditedEntity entityType, UUID entityId, int limit) {
        return sql.sql("""
                        select created_at, user_id, action_code, old_value::text as old_value,
                               new_value::text as new_value, ip_address
                          from audit_log
                         where entity_type = ? and entity_id = ?
                         order by created_at desc, audit_id desc
                         limit ?""")
                .params(entityType.name(), entityId, limit)
                .query((row, index) -> new AuditRecord(
                        row.getTimestamp("created_at").toInstant(),
                        row.getObject("user_id", UUID.class),
                        AuditAction.valueOf(row.getString("action_code")),
                        row.getString("old_value"),
                        row.getString("new_value"),
                        row.getString("ip_address")))
                .list();
    }
}
