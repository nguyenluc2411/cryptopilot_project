package com.cryptopilot.market.repository;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.Kline;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code ohlcv} hypertable: closed candles keyed by {@code (pair_id, market_type, timeframe, open_time)}.
 *
 * <p>Plain SQL rather than JPA: the table is a TimescaleDB hypertable of immutable rows with a composite key
 * and no version (TECHNICAL_DESIGN 5.5 keeps entities for single-UUID tables), written in batches and never
 * updated. Every insert is {@code ON CONFLICT (...) DO NOTHING} on the primary key, so writing a candle that
 * is already stored is a no-op — the idempotence NSF-02 requires, and what makes a backfill safe to repeat
 * or resume after an interruption.
 *
 * <p>The resume point is derived from the data itself — the latest stored open time of a series — rather
 * than kept in a checkpoint table that could disagree with it.
 *
 * <p>Rule: NSF-02; BR-08; TECHNICAL_DESIGN 5.4, 6 and 7.1.
 *
 * <p>Reference: Timescale. <i>TimescaleDB documentation</i>, "Hypertables" (a hypertable is written and
 * queried with ordinary SQL; unique constraints must include the time column, as {@code pk_ohlcv} does).
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class OhlcvRepository {

    private static final String INSERT = """
            insert into ohlcv (pair_id, market_type, timeframe, open_time, close_time, open_price, high_price,
                               low_price, close_price, base_volume, quote_volume, trade_count)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (pair_id, market_type, timeframe, open_time) do nothing""";

    private final JdbcClient sql;
    private final JdbcTemplate jdbc;

    /** The open time of the latest stored candle of a series, or empty when the series has none. */
    public Optional<Instant> latestOpenTime(UUID pairId, MarketType market, String timeframe) {
        return sql.sql("""
                        select max(open_time) from ohlcv
                         where pair_id = ? and market_type = ? and timeframe = ?""")
                .params(pairId, market.name(), timeframe)
                .query(Instant.class)
                .optional();
    }

    /**
     * Inserts closed candles of one series in one batch, skipping any already stored, and answers how many
     * were new. Not transactional here; the caller's transaction covers the page.
     */
    public int insertAll(UUID pairId, MarketType market, String timeframe, List<Kline> candles) {
        if (candles.isEmpty()) {
            return 0;
        }
        int[][] counts = jdbc.batchUpdate(INSERT, candles, candles.size(), (statement, kline) -> {
            statement.setObject(1, pairId);
            statement.setString(2, market.name());
            statement.setString(3, timeframe);
            statement.setTimestamp(4, Timestamp.from(kline.openTime()));
            statement.setTimestamp(5, Timestamp.from(kline.closeTime()));
            statement.setBigDecimal(6, kline.open());
            statement.setBigDecimal(7, kline.high());
            statement.setBigDecimal(8, kline.low());
            statement.setBigDecimal(9, kline.close());
            statement.setBigDecimal(10, kline.volume());
            statement.setBigDecimal(11, kline.quoteVolume());
            if (kline.tradeCount() > Integer.MAX_VALUE) {
                statement.setNull(12, Types.INTEGER);
            } else {
                statement.setInt(12, (int) kline.tradeCount());
            }
        });
        return Arrays.stream(counts)
                .flatMapToInt(Arrays::stream)
                .map(n -> Math.max(n, 0))
                .sum();
    }
}
