package com.cryptopilot.user.repository;

import com.cryptopilot.admin.AdminMetricSource;
import com.cryptopilot.admin.MetricSeries;
import com.cryptopilot.admin.model.enums.ReportGrouping;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The users of the dashboard (SRS 3.11.1): Traders in total, new in the period, and active, signed in during the last
 * 7 days; and the new Traders of each bucket of a report.
 *
 * <p>Rule: UC-40, UC-41; SRS 3.11.1.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class UserMetrics implements AdminMetricSource {

    /** What "active" looks back over (SRS 3.11.1: "logged in during the last 7 days"). */
    static final Duration ACTIVE_WINDOW = Duration.ofDays(7);

    private final JdbcClient sql;

    @Override
    public Map<String, Object> indicators(Instant from, Instant to, Instant now) {
        return sql.sql("""
                        select count(*) as total,
                               count(*) filter (where created_at >= :from and created_at < :to) as new_users,
                               count(*) filter (where last_login_at >= :since) as active
                          from user_account where role = 'TRADER'""")
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("since", Timestamp.from(now.minus(ACTIVE_WINDOW)))
                .query((row, index) -> Map.<String, Object>of(
                        "totalUsers", row.getLong("total"),
                        "newUsers", row.getLong("new_users"),
                        "activeUsers", row.getLong("active")))
                .single();
    }

    @Override
    public Map<String, Map<LocalDate, BigDecimal>> series(Instant from, Instant to, ReportGrouping grouping) {
        return Map.of(
                "newUsers",
                MetricSeries.read(
                        sql,
                        "select " + MetricSeries.bucket("created_at", grouping) + " as bucket, count(*) as value"
                                + " from user_account where role = 'TRADER' and created_at >= :from"
                                + " and created_at < :to group by bucket",
                        from,
                        to));
    }
}
