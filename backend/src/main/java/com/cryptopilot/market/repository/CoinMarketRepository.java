package com.cryptopilot.market.repository;

import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The stored figures the market list of coins derives from, read for all its series at once: closes of the stored
 * candles at a past instant, the hourly closes of a week, and the open interest of the Futures pairs.
 *
 * <p>Each read is one statement whatever the number of coins. Every read of {@code ohlcv} bounds {@code open_time},
 * the column the hypertable is partitioned on, so only the chunks of the window are scanned; the open interest read
 * bounds {@code snapshot_time} the same way. Every stored candle is closed (BR-08).
 *
 * <p>Rule: SRS 3.3.1; BR-08.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley (the cost of
 * one query per row). TimescaleDB documentation, "Hypertables and chunks" (chunk exclusion on the partitioning column).
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class CoinMarketRepository {

    private final JdbcClient sql;

    /**
     * One series of candles: a pair on one market.
     *
     * @param pairId the pair
     * @param market its market
     */
    public record Series(UUID pairId, MarketType market) {}

    /**
     * Per series, the close of its latest candle of {@code interval} that closed in {@code (notBefore, at]}. A series
     * without one is absent.
     */
    public Map<Series, BigDecimal> closesAt(
            Collection<Series> series, MarketInterval interval, Instant at, Instant notBefore) {
        if (series.isEmpty()) {
            return Map.of();
        }
        Map<Series, BigDecimal> closes = new HashMap<>();
        sql.sql("""
                        select distinct on (pair_id, market_type) pair_id, market_type, close_price
                          from ohlcv
                         where (pair_id, market_type) in (:series)
                           and timeframe = :timeframe
                           and open_time > :openFloor and open_time <= :at
                           and close_time > :notBefore and close_time <= :at
                         order by pair_id, market_type, open_time desc""")
                .param("series", tuples(series))
                .param("timeframe", interval.code())
                .param("openFloor", Timestamp.from(notBefore.minus(interval.duration())))
                .param("notBefore", Timestamp.from(notBefore))
                .param("at", Timestamp.from(at))
                .query((RowCallbackHandler) row -> closes.put(
                        series(row.getObject("pair_id", UUID.class), row.getString("market_type")),
                        row.getBigDecimal("close_price")));
        return closes;
    }

    /** Per series, the closes of its candles of {@code interval} opened in {@code [from, to]}, oldest first. */
    public Map<Series, List<BigDecimal>> closesBetween(
            Collection<Series> series, MarketInterval interval, Instant from, Instant to) {
        if (series.isEmpty()) {
            return Map.of();
        }
        Map<Series, List<BigDecimal>> closes = new HashMap<>();
        sql.sql("""
                        select pair_id, market_type, close_price
                          from ohlcv
                         where (pair_id, market_type) in (:series)
                           and timeframe = :timeframe
                           and open_time >= :from and open_time <= :to
                         order by pair_id, market_type, open_time""")
                .param("series", tuples(series))
                .param("timeframe", interval.code())
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((RowCallbackHandler) row -> closes.computeIfAbsent(
                                series(row.getObject("pair_id", UUID.class), row.getString("market_type")),
                                key -> new ArrayList<>())
                        .add(row.getBigDecimal("close_price")));
        return closes;
    }

    /**
     * One point of the open interest of several pairs together.
     *
     * @param at the instant
     * @param valueUsd the summed open interest in US dollars of the pairs sampled in the window before it
     * @param pairs how many pairs were sampled
     */
    public record OpenInterestPoint(Instant at, BigDecimal valueUsd, int pairs) {}

    /**
     * The summed open interest of the pairs at each whole hour from {@code from} to {@code to}, both truncated to the
     * hour: per pair the latest sample taken within {@code window} before the hour (NSF-04 samples every five
     * minutes). An hour without any sample is left out.
     */
    public List<OpenInterestPoint> openInterestHourly(
            Collection<UUID> pairIds, Instant from, Instant to, Duration window) {
        if (pairIds.isEmpty()) {
            return List.of();
        }
        return sql.sql("""
                        select b.at, sum(s.open_interest_value) as value_usd, count(*) as pairs
                          from generate_series(date_trunc('hour', cast(:from as timestamptz)),
                                               date_trunc('hour', cast(:to as timestamptz)),
                                               interval '1 hour') as b(at)
                          cross join lateral (
                              select distinct on (d.pair_id) d.open_interest_value
                                from futures_market_data d
                               where d.pair_id in (:pairs)
                                 and d.snapshot_time > :floor and d.snapshot_time <= :to
                                 and d.snapshot_time <= b.at
                                 and d.snapshot_time > b.at - cast(:window as interval)
                                 and d.open_interest_value is not null
                               order by d.pair_id, d.snapshot_time desc) as s
                         group by b.at
                         order by b.at""")
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("floor", Timestamp.from(from.minus(window).minus(Duration.ofHours(1))))
                .param("pairs", pairIds)
                .param("window", window.toSeconds() + " seconds")
                .query((row, index) -> new OpenInterestPoint(
                        row.getTimestamp("at").toInstant(), row.getBigDecimal("value_usd"), row.getInt("pairs")))
                .list();
    }

    private static List<Object[]> tuples(Collection<Series> series) {
        return series.stream()
                .map(one -> new Object[] {one.pairId(), one.market().name()})
                .toList();
    }

    private static Series series(UUID pairId, String market) {
        return new Series(pairId, MarketType.valueOf(market));
    }
}
