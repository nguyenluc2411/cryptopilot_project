package com.cryptopilot.billing.repository;

import com.cryptopilot.admin.AdminMetricSource;
import com.cryptopilot.admin.MetricSeries;
import com.cryptopilot.admin.model.enums.ReportGrouping;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The revenue of the dashboard and the reports (SRS 3.11.1: "active subscriptions, revenue of PAID orders by period
 * and package"). Revenue is the amount of the orders PAID in the period, by the instant they were paid, in VND; a
 * package's series is named {@code revenue:<package code>}.
 *
 * <p>Rule: UC-40, UC-41; SRS 3.11.1, 3.10.4.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class BillingMetrics implements AdminMetricSource {

    private static final String PAID = """
             from subscription_order o join subscription_package p on p.package_id = o.package_id
            where o.order_status = 'PAID' and o.paid_at >= :from and o.paid_at < :to""";

    /** The subscriptions in force at an instant. */
    private static final String ACTIVE = """
            select count(*) from user_subscription
             where subscription_status = 'ACTIVE' and start_at <= :now and end_at > :now""";

    private final JdbcClient sql;

    @Override
    public Map<String, Object> indicators(Instant from, Instant to, Instant now) {
        long active = sql.sql(ACTIVE)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
        record PackageRevenue(String code, long orders, BigDecimal revenue) {}
        List<PackageRevenue> paid = sql.sql("select p.package_code, count(*) as orders, sum(o.amount) as revenue" + PAID
                        + " group by p.package_code")
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((row, index) -> new PackageRevenue(
                        row.getString("package_code"), row.getLong("orders"), row.getBigDecimal("revenue")))
                .list();
        Map<String, BigDecimal> byPackage = new TreeMap<>();
        paid.forEach(row -> byPackage.put(row.code(), row.revenue()));
        Map<String, Object> indicators = new HashMap<>();
        indicators.put("activeSubscriptions", active);
        indicators.put(
                "paidOrders", paid.stream().mapToLong(PackageRevenue::orders).sum());
        indicators.put("revenue", paid.stream().map(PackageRevenue::revenue).reduce(BigDecimal.ZERO, BigDecimal::add));
        indicators.put("revenueByPackage", byPackage);
        return indicators;
    }

    @Override
    public Map<String, Map<LocalDate, BigDecimal>> series(Instant from, Instant to, ReportGrouping grouping) {
        String bucket = MetricSeries.bucket("o.paid_at", grouping);
        Map<String, Map<LocalDate, BigDecimal>> series = new HashMap<>();
        series.put(
                "paidOrders",
                MetricSeries.read(
                        sql,
                        "select " + bucket + " as bucket, count(*) as value" + PAID + " group by bucket",
                        from,
                        to));
        series.put(
                "revenue",
                MetricSeries.read(
                        sql,
                        "select " + bucket + " as bucket, sum(o.amount) as value" + PAID + " group by bucket",
                        from,
                        to));
        List<String> codes = sql.sql("select distinct p.package_code" + PAID)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(String.class)
                .list();
        for (String code : codes) {
            series.put(
                    "revenue:" + code,
                    MetricSeries.read(
                            sql,
                            "select " + bucket + " as bucket, sum(o.amount) as value" + PAID
                                    + " and p.package_code = :code group by bucket",
                            from,
                            to,
                            Map.of("code", code)));
        }
        return series;
    }
}
