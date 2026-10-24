package com.cryptopilot.market.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The dashboard's latest stored candle per market (SRS 3.11.1) answers what a scan of the whole hypertable answers,
 * both for a market whose latest candle is recent and for one whose ingestion stopped before the recent window.
 *
 * <p>Rule: UC-40; SRS 3.11.1.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class MarketMetricsTest {

    @Autowired
    private MarketMetrics metrics;

    @Autowired
    private JdbcClient sql;

    private UUID pair;

    @AfterEach
    void removeRows() {
        if (pair != null) {
            new MarketTestData(sql, Instant.now()).remove(pair);
        }
    }

    @Test
    void UC40_theLatestCandlePerMarket_isWhatAFullScanFinds_recentOrNot() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        String symbol = "MM" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, now).pair(symbol, true, true, "TRADING", "TRADING", 0);
        candle("SPOT", now.minus(Duration.ofHours(1)));
        // Opened long before the recent window; the cheap look misses it and the fallback must find it, unless a
        // newer futures candle another test left is the latest anyway, which the full scan then names too.
        candle("FUTURES", now.minus(MarketMetrics.RECENT).minus(Duration.ofDays(10)));

        @SuppressWarnings("unchecked")
        Map<String, Instant> latest =
                (Map<String, Instant>) metrics.indicators(now, now, now).get("latestCandle");

        assertThat(latest).isEqualTo(fullScan()).containsKeys("SPOT", "FUTURES");
    }

    private Map<String, Instant> fullScan() {
        Map<String, Instant> latest = new TreeMap<>();
        sql.sql("select market_type, max(close_time) as latest from ohlcv group by market_type")
                .query((row, index) -> latest.put(
                        row.getString("market_type"), row.getTimestamp("latest").toInstant()))
                .list();
        return latest;
    }

    private void candle(String market, Instant open) {
        sql.sql("""
                        insert into ohlcv (pair_id, market_type, timeframe, open_time, close_time, open_price,
                                           high_price, low_price, close_price, base_volume, quote_volume, trade_count)
                        values (?, ?, '1h', ?, ?, 100, 110, 90, 105, 10, 1000, 5)""")
                .params(
                        pair,
                        market,
                        Timestamp.from(open),
                        Timestamp.from(open.plus(Duration.ofHours(1)).minusMillis(1)))
                .update();
    }
}
