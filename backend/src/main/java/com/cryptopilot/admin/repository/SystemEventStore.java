package com.cryptopilot.admin.repository;

import com.cryptopilot.admin.model.SourceFailures;
import com.cryptopilot.admin.model.SystemEventFilter;
import com.cryptopilot.admin.model.SystemEventRow;
import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import com.cryptopilot.common.util.UuidV7;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code system_event} table (V59): writes a failure, merging a repeat into the latest row of its fingerprint, and
 * reads the system log and the figures of the health panel. A technical table, so plain SQL and no entity.
 *
 * <p>Rule: UC-46.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SystemEventStore {

    private static final RowMapper<SystemEventRow> ROW = (row, index) -> new SystemEventRow(
            row.getObject("system_event_id", UUID.class),
            SystemEventCategory.valueOf(row.getString("category")),
            SystemEventSource.valueOf(row.getString("source")),
            row.getString("operation"),
            row.getString("error_type"),
            row.getString("message"),
            row.getString("reference"),
            row.getInt("occurrences"),
            row.getTimestamp("first_seen_at").toInstant(),
            row.getTimestamp("last_seen_at").toInstant());

    private final JdbcClient sql;

    /**
     * Records a failure: adds it to the latest row of its fingerprint last seen at or after {@code mergeSince}, or
     * writes a new row when there is none.
     */
    public void record(
            SystemEventCategory category,
            SystemEventSource source,
            String operation,
            String errorType,
            String message,
            String reference,
            String fingerprint,
            Instant now,
            Instant mergeSince) {
        int merged = sql.sql("""
                        update system_event
                           set occurrences = occurrences + 1,
                               last_seen_at = greatest(last_seen_at, :now),
                               message = :message,
                               reference = coalesce(:reference, reference)
                         where system_event_id = (
                               select system_event_id
                                 from system_event
                                where fingerprint = :fingerprint and last_seen_at >= :since
                                order by last_seen_at desc
                                limit 1)""")
                .param("now", Timestamp.from(now))
                .param("message", message)
                .param("reference", reference)
                .param("fingerprint", fingerprint)
                .param("since", Timestamp.from(mergeSince))
                .update();
        if (merged > 0) {
            return;
        }
        sql.sql("""
                        insert into system_event (system_event_id, category, source, operation, error_type, message,
                                                  reference, fingerprint, occurrences, first_seen_at, last_seen_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)""")
                .params(
                        UuidV7.next(),
                        category.name(),
                        source.name(),
                        operation,
                        errorType,
                        message,
                        reference,
                        fingerprint,
                        Timestamp.from(now),
                        Timestamp.from(now))
                .update();
    }

    /** The rows matching the filter, the latest seen first, from {@code offset}, at most {@code limit}. */
    public List<SystemEventRow> search(SystemEventFilter filter, long offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = where(filter, params);
        params.put("offset", offset);
        params.put("limit", limit);
        return sql.sql("select * from system_event" + where
                        + " order by last_seen_at desc, system_event_id desc offset :offset limit :limit")
                .params(params)
                .query(ROW)
                .list();
    }

    /** How many rows match the filter. */
    public long count(SystemEventFilter filter) {
        Map<String, Object> params = new HashMap<>();
        String where = where(filter, params);
        return sql.sql("select count(*) from system_event" + where)
                .params(params)
                .query(Long.class)
                .single();
    }

    /**
     * For each source with a row in the log: its latest failure, and how many failures were seen in rows last seen at
     * or after {@code since}.
     */
    public Map<SystemEventSource, SourceFailures> failuresBySource(Instant since) {
        Map<SystemEventSource, SourceFailures> bySource = new EnumMap<>(SystemEventSource.class);
        sql.sql("""
                        select distinct on (source) source, last_seen_at, message,
                               coalesce(sum(occurrences) filter (where last_seen_at >= :since)
                                        over (partition by source), 0) as recent
                          from system_event
                         order by source, last_seen_at desc""")
                .param("since", Timestamp.from(since))
                .query((row, index) -> new SourceFailures(
                        SystemEventSource.valueOf(row.getString("source")),
                        row.getTimestamp("last_seen_at").toInstant(),
                        row.getLong("recent"),
                        row.getString("message")))
                .list()
                .forEach(failures -> bySource.put(failures.source(), failures));
        return bySource;
    }

    /** Deletes the rows last seen before {@code before}; returns how many. */
    public int deleteLastSeenBefore(Instant before) {
        return sql.sql("delete from system_event where last_seen_at < ?")
                .param(Timestamp.from(before))
                .update();
    }

    private static String where(SystemEventFilter filter, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(" where true");
        if (filter.category() != null) {
            where.append(" and category = :category");
            params.put("category", filter.category().name());
        }
        if (filter.source() != null) {
            where.append(" and source = :source");
            params.put("source", filter.source().name());
        }
        if (filter.from() != null) {
            where.append(" and last_seen_at >= :from");
            params.put("from", Timestamp.from(filter.from()));
        }
        if (filter.until() != null) {
            where.append(" and last_seen_at < :until");
            params.put("until", Timestamp.from(filter.until()));
        }
        return where.toString();
    }
}
