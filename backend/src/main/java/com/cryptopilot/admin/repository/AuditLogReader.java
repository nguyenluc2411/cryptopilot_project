package com.cryptopilot.admin.repository;

import com.cryptopilot.admin.AuditRecord;
import com.cryptopilot.admin.model.AuditLogFilter;
import com.cryptopilot.admin.model.AuditLogRow;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code audit_log} for the detail screens and for the audit log of SCR-44. The entity is write-only by design
 * (D-74), so the reads go through here, with the JSON columns as text. Newest first, the id breaking a tie, so a page
 * boundary never repeats or skips an entry recorded in the same instant.
 *
 * <p>Rule: BR-57; D-74; SRS 3.11.9.
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

    private static final RowMapper<AuditLogRow> ROW = (row, index) -> new AuditLogRow(
            row.getObject("audit_id", UUID.class),
            row.getTimestamp("created_at").toInstant(),
            row.getObject("user_id", UUID.class),
            row.getString("action_code"),
            row.getString("entity_type"),
            row.getObject("entity_id", UUID.class),
            row.getString("old_value"),
            row.getString("new_value"),
            row.getString("ip_address"));

    /** The entries matching the filter, newest first, from {@code offset}, at most {@code limit}. */
    public List<AuditLogRow> search(AuditLogFilter filter, long offset, int limit) {
        if (matchesNothing(filter)) {
            return List.of();
        }
        Map<String, Object> params = new HashMap<>();
        String where = where(filter, params);
        params.put("offset", offset);
        params.put("limit", limit);
        return sql.sql("select audit_id, created_at, user_id, action_code, entity_type, entity_id,"
                        + " old_value::text as old_value, new_value::text as new_value, ip_address"
                        + " from audit_log" + where
                        + " order by created_at desc, audit_id desc offset :offset limit :limit")
                .params(params)
                .query(ROW)
                .list();
    }

    /** How many entries match the filter. */
    public long count(AuditLogFilter filter) {
        if (matchesNothing(filter)) {
            return 0;
        }
        Map<String, Object> params = new HashMap<>();
        String where = where(filter, params);
        return sql.sql("select count(*) from audit_log" + where)
                .params(params)
                .query(Long.class)
                .single();
    }

    private static boolean matchesNothing(AuditLogFilter filter) {
        return (filter.actors() != null && filter.actors().isEmpty())
                || (filter.systemOnly() && (filter.usersOnly() || filter.actors() != null));
    }

    private static String where(AuditLogFilter filter, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(" where true");
        if (filter.actors() != null) {
            where.append(" and user_id = any(cast(:actors as uuid[]))");
            params.put(
                    "actors", array(filter.actors().stream().map(UUID::toString).toList()));
        }
        if (filter.systemOnly()) {
            where.append(" and user_id is null");
        }
        if (filter.usersOnly()) {
            where.append(" and user_id is not null");
        }
        if (!filter.actions().isEmpty()) {
            where.append(" and action_code = any(cast(:actions as varchar[]))");
            params.put(
                    "actions",
                    array(filter.actions().stream()
                            .map(AuditAction::name)
                            .sorted()
                            .toList()));
        }
        if (filter.entityType() != null) {
            where.append(" and entity_type = :entityType");
            params.put("entityType", filter.entityType().name());
        }
        if (filter.entityId() != null) {
            where.append(" and entity_id = :entityId");
            params.put("entityId", filter.entityId());
        }
        if (filter.from() != null) {
            where.append(" and created_at >= :from");
            params.put("from", Timestamp.from(filter.from()));
        }
        if (filter.until() != null) {
            where.append(" and created_at < :until");
            params.put("until", Timestamp.from(filter.until()));
        }
        return where.toString();
    }

    private static String array(List<String> values) {
        StringJoiner literal = new StringJoiner(",", "{", "}");
        values.forEach(literal::add);
        return literal.toString();
    }
}
