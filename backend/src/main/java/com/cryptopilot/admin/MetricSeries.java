package com.cryptopilot.admin;

import com.cryptopilot.admin.model.enums.ReportGrouping;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads one series of a management report the same way for every {@link AdminMetricSource}: the caller writes a
 * statement selecting {@code bucket} and {@code value} over {@code [:from, :to)}, using {@link #bucket} for the bucket,
 * and this runs it with any further parameters.
 *
 * <p>Rule: UC-41; CR-01.
 */
public final class MetricSeries {

    private MetricSeries() {}

    /** The SQL of the bucket a timestamp column falls in: the business day its group starts on, UTC+7. */
    public static String bucket(String column, ReportGrouping grouping) {
        return "(date_trunc('" + grouping.sqlUnit() + "', " + column + " at time zone '" + AdminMetricSource.ZONE
                + "'))::date";
    }

    /** Runs a statement selecting {@code bucket} and {@code value}, with {@code :from}, {@code :to} and these bound. */
    public static Map<LocalDate, BigDecimal> read(
            JdbcClient sql, String statement, Instant from, Instant to, Map<String, Object> params) {
        Map<LocalDate, BigDecimal> values = new TreeMap<>();
        sql.sql(statement)
                .params(params)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((row, index) -> values.put(row.getObject("bucket", LocalDate.class), row.getBigDecimal("value")))
                .list();
        return values;
    }

    /** Runs a statement selecting {@code bucket} and {@code value}, with {@code :from} and {@code :to} bound. */
    public static Map<LocalDate, BigDecimal> read(JdbcClient sql, String statement, Instant from, Instant to) {
        return read(sql, statement, from, to, Map.of());
    }
}
