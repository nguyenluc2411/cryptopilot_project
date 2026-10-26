package com.cryptopilot.community.repository;

import com.cryptopilot.admin.AdminMetricSource;
import com.cryptopilot.admin.MetricSeries;
import com.cryptopilot.admin.model.enums.ReportGrouping;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The forum of the dashboard and the reports: the reports waiting for a moderator (SRS 3.11.1: "open reports") and
 * the forum activity the registered topic asks the management reports to show (D-99): posts and comments written in
 * the period, whatever became of them since.
 *
 * <p>Rule: UC-40, UC-41; SRS 3.11.1; D-99.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class CommunityMetrics implements AdminMetricSource {

    private final JdbcClient sql;

    @Override
    public Map<String, Object> indicators(Instant from, Instant to, Instant now) {
        return sql.sql("""
                        select (select count(*) from post_report
                                 where report_status in ('OPEN', 'REVIEWING') and comment_id is null) as open_reports,
                               (select count(*) from post_report
                                 where report_status in ('OPEN', 'REVIEWING') and comment_id is not null)
                                   as open_comment_reports,
                               (select count(*) from forum_post where created_at >= :from and created_at < :to)
                                   as posts,
                               (select count(*) from forum_comment where created_at >= :from and created_at < :to)
                                   as comments""")
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((row, index) -> Map.<String, Object>of(
                        "openReports", row.getLong("open_reports"),
                        "openCommentReports", row.getLong("open_comment_reports"),
                        "newPosts", row.getLong("posts"),
                        "newComments", row.getLong("comments")))
                .single();
    }

    @Override
    public Map<String, Map<LocalDate, BigDecimal>> series(Instant from, Instant to, ReportGrouping grouping) {
        return Map.of(
                "newPosts",
                MetricSeries.read(
                        sql,
                        "select " + MetricSeries.bucket("created_at", grouping) + " as bucket, count(*) as value"
                                + " from forum_post where created_at >= :from and created_at < :to group by bucket",
                        from,
                        to),
                "newComments",
                MetricSeries.read(
                        sql,
                        "select " + MetricSeries.bucket("created_at", grouping) + " as bucket, count(*) as value"
                                + " from forum_comment where created_at >= :from and created_at < :to group by bucket",
                        from,
                        to));
    }
}
