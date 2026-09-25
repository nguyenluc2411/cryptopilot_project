package com.cryptopilot.market.repository;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.model.StoredCandle;
import com.cryptopilot.market.model.StoredGap;
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

    /**
     * The latest stored candles of a series opened in {@code [from, to)}, at most {@code limit}, oldest first (UC-09).
     * Every stored candle is closed (BR-08), so nothing here is still forming. Newest first in SQL, so the limit keeps
     * the most recent ones and the primary key is scanned backwards; reversed before it is returned.
     *
     * @param from the earliest open time wanted, or {@code null} for no lower bound
     * @param to the open time the candles must be before
     */
    public List<StoredCandle> closedCandles(
            UUID pairId, MarketType market, String timeframe, Instant from, Instant to, int limit) {
        List<StoredCandle> newestFirst = sql.sql("""
                        select open_time, close_time, open_price, high_price, low_price, close_price, base_volume,
                               quote_volume, trade_count
                          from ohlcv
                         where pair_id = ? and market_type = ? and timeframe = ?
                           and open_time >= ? and open_time < ?
                         order by open_time desc
                         limit ?""")
                .params(
                        pairId,
                        market.name(),
                        timeframe,
                        Timestamp.from(from == null ? Instant.EPOCH : from),
                        Timestamp.from(to),
                        limit)
                .query((row, index) -> new StoredCandle(
                        row.getTimestamp("open_time").toInstant(),
                        row.getTimestamp("close_time").toInstant(),
                        row.getBigDecimal("open_price"),
                        row.getBigDecimal("high_price"),
                        row.getBigDecimal("low_price"),
                        row.getBigDecimal("close_price"),
                        row.getBigDecimal("base_volume"),
                        row.getBigDecimal("quote_volume"),
                        row.getObject("trade_count", Integer.class)))
                .list();
        return newestFirst.reversed();
    }

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
     * The holes inside the stored series: every pair of consecutive stored candles, among those opened at or
     * after {@code since}, that are further apart than one timeframe. One row per hole, with the open times of the
     * candles either side of it.
     *
     * <p>{@code lag(open_time)} over each series, compared with the timeframe's length. The previous candle is
     * looked up to one day before {@code since} (the longest timeframe), so a hole that starts just before the
     * window is found too. Reads only the recent chunks of the hypertable.
     *
     * <p>Rule: NSF-02, NSF-03 (gap detection); A-33.
     *
     * <p>Reference: PostgreSQL Global Development Group. <i>PostgreSQL 16 Documentation</i>, §3.5 "Window
     * Functions" and §9.22 ({@code lag}).
     */
    public List<StoredGap> gapsSince(Instant since) {
        return sql.sql("""
                        select pair_id, market_type, timeframe, previous_open, open_time
                          from (select pair_id, market_type, timeframe, open_time,
                                       lag(open_time) over (partition by pair_id, market_type, timeframe
                                                            order by open_time) as previous_open
                                  from ohlcv
                                 where open_time >= cast(? as timestamptz) - interval '1 day') series
                         where open_time >= ?
                           and previous_open is not null
                           and open_time - previous_open > case timeframe
                                   when '15m' then interval '15 minutes'
                                   when '1h' then interval '1 hour'
                                   when '4h' then interval '4 hours'
                                   else interval '1 day' end
                         order by pair_id, market_type, timeframe, open_time""")
                .params(Timestamp.from(since), Timestamp.from(since))
                .query((row, n) -> new StoredGap(
                        row.getObject("pair_id", UUID.class),
                        MarketType.valueOf(row.getString("market_type")),
                        row.getString("timeframe"),
                        row.getTimestamp("previous_open").toInstant(),
                        row.getTimestamp("open_time").toInstant()))
                .list();
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
