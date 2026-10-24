package com.cryptopilot.market.repository;

import com.cryptopilot.admin.AdminMetricSource;
import com.cryptopilot.admin.model.enums.ReportGrouping;
import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The data ingestion status of the dashboard (SRS 3.11.1: "time of the latest stored candle per market"): the close of
 * the latest candle stored for each market, any pair, any timeframe. A market with none stored is left out.
 *
 * <p>{@code ohlcv} is a hypertable of 30-day chunks, compressed after 60 days, so a plain {@code max(close_time)} reads
 * and decompresses every chunk on each dashboard load. The latest candle is looked for among those opened in the last
 * {@link #RECENT} first, which touches the newest chunk only; a market whose ingestion stopped longer ago falls back
 * to the newest candle by open time, which the hypertable's time index finds without a scan.
 *
 * <p>Rule: UC-40; SRS 3.11.1; NSF-02, NSF-03.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MarketMetrics implements AdminMetricSource {

    /** How far back the cheap look goes: past the longest timeframe stored, a day, with room to spare. */
    static final Duration RECENT = Duration.ofDays(2);

    private final JdbcClient sql;

    @Override
    public Map<String, Object> indicators(Instant from, Instant to, Instant now) {
        Map<String, Instant> latest = new TreeMap<>();
        for (MarketType market : MarketType.values()) {
            latestClose(market, now).ifPresent(close -> latest.put(market.name(), close));
        }
        return Map.of("latestCandle", latest);
    }

    private Optional<Instant> latestClose(MarketType market, Instant now) {
        Optional<Instant> recent = sql
                .sql("select max(close_time) from ohlcv where market_type = ? and open_time >= ?")
                .params(market.name(), Timestamp.from(now.minus(RECENT)))
                .query(Timestamp.class)
                .list()
                .stream()
                .filter(Objects::nonNull)
                .findFirst()
                .map(Timestamp::toInstant);
        if (recent.isPresent()) {
            return recent;
        }
        return sql.sql("select close_time from ohlcv where market_type = ? order by open_time desc limit 1")
                .param(market.name())
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    @Override
    public Map<String, Map<LocalDate, BigDecimal>> series(Instant from, Instant to, ReportGrouping grouping) {
        return Map.of();
    }
}
