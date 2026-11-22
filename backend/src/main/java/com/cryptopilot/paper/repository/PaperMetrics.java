package com.cryptopilot.paper.repository;

import com.cryptopilot.admin.AdminMetricSource;
import com.cryptopilot.admin.MetricSeries;
import com.cryptopilot.admin.model.enums.ReportGrouping;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The demo exchange's figures for the administration console (TR-30, plan section 9: "số tài khoản demo, số lệnh mở,
 * số vị thế, ..., các lần thanh lý gần nhất"): on the dashboard (SCR-33) the accounts, the working orders and the open
 * positions now, and the accounts opened, the fills and the liquidations of the period; in the management report the
 * fills and the accounts opened per bucket; and the latest liquidations for the demo exchange's overview. An account is open
 * once its INITIAL_GRANT is written (V47). Orders and fills of trading plans are left out: they are the simulation of
 * the plans, not the demo exchange. Read-only aggregates.
 *
 * <p>The demo exchange's settings are system settings (SCR-43, audited by BR-57); the matching engine's state is on
 * the API health panel through the market ingestion figures. Neither needs anything here.
 *
 * <p>Rule: TR-30; UC-40, UC-41, UC-46; SRS 3.11.1.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperMetrics implements AdminMetricSource {

    private final JdbcClient sql;

    @Override
    public Map<String, Object> indicators(Instant from, Instant to, Instant now) {
        Map<String, Object> indicators = new LinkedHashMap<>();
        indicators.put("paperAccounts", count("""
                select count(distinct user_id) from wallet_ledger_entry where entry_type = 'INITIAL_GRANT'""", null, null));
        indicators.put("paperAccountsOpened", count("""
                select count(distinct user_id) from wallet_ledger_entry
                 where entry_type = 'INITIAL_GRANT' and created_at >= :from and created_at < :to""", from, to));
        Map<String, Long> working = new LinkedHashMap<>();
        working.put("SPOT", 0L);
        working.put("FUTURES", 0L);
        sql.sql("""
                        select market_type, count(*) as n from trade_order
                         where order_status in ('NEW', 'PENDING_NEW', 'PARTIALLY_FILLED') and plan_id is null
                         group by market_type""")
                .query((row, i) -> working.put(row.getString("market_type"), row.getLong("n")))
                .list();
        indicators.put("paperWorkingOrders", working);
        indicators.put(
                "paperOpenPositions",
                count("select count(*) from futures_position where position_status = 'OPEN'", null, null));
        indicators.put("paperFills", count("""
                select count(*) from order_fill f
                  join trade_order o on o.order_id = f.order_id
                 where o.plan_id is null and f.traded_at >= :from and f.traded_at < :to""", from, to));
        indicators.put("paperLiquidations", count("""
                select count(*) from futures_position
                 where close_reason = 'LIQUIDATION' and closed_at >= :from and closed_at < :to""", from, to));
        return indicators;
    }

    /**
     * The latest liquidations of the demo exchange, newest first: when, which pair, whose, its margin type and what it
     * realised. For the administrator's overview (TR-30).
     */
    public List<Map<String, Object>> latestLiquidations(int limit) {
        return sql.sql("""
                        select p.closed_at, c.symbol, p.user_id, p.margin_type, p.realized_pnl_amount
                          from futures_position p
                          join crypto_pair c on c.pair_id = p.pair_id
                         where p.close_reason = 'LIQUIDATION'
                         order by p.closed_at desc, p.position_id
                         limit :limit""")
                .param("limit", limit)
                .query((row, i) -> {
                    Map<String, Object> liquidation = new LinkedHashMap<>();
                    liquidation.put("at", row.getTimestamp("closed_at").toInstant());
                    liquidation.put("symbol", row.getString("symbol"));
                    liquidation.put("userId", row.getObject("user_id"));
                    liquidation.put("marginType", row.getString("margin_type"));
                    liquidation.put("realizedPnl", row.getBigDecimal("realized_pnl_amount"));
                    return liquidation;
                })
                .list();
    }

    @Override
    public Map<String, Map<LocalDate, BigDecimal>> series(Instant from, Instant to, ReportGrouping grouping) {
        Map<String, Map<LocalDate, BigDecimal>> series = new HashMap<>();
        series.put(
                "paperFills",
                MetricSeries.read(
                        sql,
                        "select " + MetricSeries.bucket("f.traded_at", grouping) + " as bucket, count(*) as value"
                                + " from order_fill f join trade_order o on o.order_id = f.order_id"
                                + " where o.plan_id is null and f.traded_at >= :from and f.traded_at < :to"
                                + " group by bucket",
                        from,
                        to));
        series.put(
                "paperAccountsOpened",
                MetricSeries.read(
                        sql,
                        "select " + MetricSeries.bucket("e.created_at", grouping) + " as bucket,"
                                + " count(distinct e.user_id) as value from wallet_ledger_entry e"
                                + " where e.entry_type = 'INITIAL_GRANT' and e.created_at >= :from"
                                + " and e.created_at < :to group by bucket",
                        from,
                        to));
        return series;
    }

    private long count(String statement, Instant from, Instant to) {
        JdbcClient.StatementSpec spec = sql.sql(statement);
        if (from != null) {
            spec = spec.param("from", Timestamp.from(from)).param("to", Timestamp.from(to));
        }
        return spec.query(Long.class).single();
    }
}
