package com.cryptopilot.admin.repository;

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
 * The AI questions of the dashboard and the reports (SRS 3.11.1: "AI questions per day"): the messages Traders sent the
 * assistant. No module owns {@code ai_message} yet — the assistant is built later — so this reads it here; the
 * assistant's module takes this class over when it exists, and until then every count is 0.
 *
 * <p>Rule: UC-40, UC-41; SRS 3.11.1.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AiUsageMetrics implements AdminMetricSource {

    private static final String ASKED =
            " from ai_message where sender_role = 'USER' and created_at >= :from and created_at < :to";

    private final JdbcClient sql;

    @Override
    public Map<String, Object> indicators(Instant from, Instant to, Instant now) {
        long asked = sql.sql("select count(*)" + ASKED)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(Long.class)
                .single();
        return Map.of("aiQuestions", asked);
    }

    @Override
    public Map<String, Map<LocalDate, BigDecimal>> series(Instant from, Instant to, ReportGrouping grouping) {
        return Map.of(
                "aiQuestions",
                MetricSeries.read(
                        sql,
                        "select " + MetricSeries.bucket("created_at", grouping) + " as bucket, count(*) as value"
                                + ASKED + " group by bucket",
                        from,
                        to));
    }
}
