package com.cryptopilot.community.repository;

import com.cryptopilot.community.model.QueueEntry;
import com.cryptopilot.community.model.enums.ReportReason;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the moderation queue (SCR-36): the posts that have OPEN or REVIEWING reports, one entry per post with its
 * reports counted and their reasons gathered, the post reported longest ago first.
 *
 * <p>Rule: UC-43; SRS 3.11.3.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ModerationQueueRepository {

    private final JdbcClient sql;

    /** One page of the queue. */
    public Page<QueueEntry> queue(Pageable page) {
        List<QueueEntry> entries = sql.sql("""
                        select post_id, count(*) as reports, array_agg(distinct reason) as reasons,
                               min(created_at) as first_at, max(created_at) as last_at
                          from post_report
                         where report_status in ('OPEN', 'REVIEWING') and comment_id is null
                         group by post_id
                         order by min(created_at), post_id
                         limit :limit offset :offset""")
                .param("limit", page.getPageSize())
                .param("offset", page.getOffset())
                .query((row, index) -> new QueueEntry(
                        row.getObject("post_id", UUID.class),
                        row.getLong("reports"),
                        reasons(row.getArray("reasons")),
                        row.getTimestamp("first_at").toInstant(),
                        row.getTimestamp("last_at").toInstant()))
                .list();
        long total = sql.sql("select count(distinct post_id) from post_report"
                        + " where report_status in ('OPEN', 'REVIEWING') and comment_id is null")
                .query(Long.class)
                .single();
        return new PageImpl<>(entries, page, total);
    }

    /** One page of the comment queue (D-99): one row per comment with open reports, reported longest ago first. */
    public Page<QueueEntry> commentQueue(Pageable page) {
        List<QueueEntry> entries = sql.sql("""
                        select comment_id, count(*) as reports, array_agg(distinct reason) as reasons,
                               min(created_at) as first_at, max(created_at) as last_at
                          from post_report
                         where report_status in ('OPEN', 'REVIEWING') and comment_id is not null
                         group by comment_id
                         order by min(created_at), comment_id
                         limit :limit offset :offset""")
                .param("limit", page.getPageSize())
                .param("offset", page.getOffset())
                .query((row, index) -> new QueueEntry(
                        row.getObject("comment_id", UUID.class),
                        row.getLong("reports"),
                        reasons(row.getArray("reasons")),
                        row.getTimestamp("first_at").toInstant(),
                        row.getTimestamp("last_at").toInstant()))
                .list();
        long total = sql.sql("select count(distinct comment_id) from post_report"
                        + " where report_status in ('OPEN', 'REVIEWING') and comment_id is not null")
                .query(Long.class)
                .single();
        return new PageImpl<>(entries, page, total);
    }

    private static List<ReportReason> reasons(Array array) throws SQLException {
        return Arrays.stream((Object[]) array.getArray())
                .map(value -> ReportReason.valueOf(value.toString()))
                .sorted()
                .toList();
    }
}
