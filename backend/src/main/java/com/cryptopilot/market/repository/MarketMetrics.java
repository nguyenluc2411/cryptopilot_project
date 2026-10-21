package com.cryptopilot.market.repository;

import com.cryptopilot.admin.AdminMetricSource;
import com.cryptopilot.admin.model.enums.ReportGrouping;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The data ingestion status of the dashboard (SRS 3.11.1: "time of the latest stored candle per market"): the close of
 * the latest candle stored for each market, any pair, any timeframe. A market with none stored is left out.
 *
 * <p>Rule: UC-40; SRS 3.11.1; NSF-02, NSF-03.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MarketMetrics implements AdminMetricSource {

    private final JdbcClient sql;

    @Override
    public Map<String, Object> indicators(Instant from, Instant to, Instant now) {
        Map<String, Instant> latest = new TreeMap<>();
        sql.sql("select market_type, max(close_time) as latest from ohlcv group by market_type")
                .query((row, index) -> latest.put(
                        row.getString("market_type"), row.getTimestamp("latest").toInstant()))
                .list();
        return Map.of("latestCandle", latest);
    }

    @Override
    public Map<String, Map<LocalDate, BigDecimal>> series(Instant from, Instant to, ReportGrouping grouping) {
        return Map.of();
    }
}
