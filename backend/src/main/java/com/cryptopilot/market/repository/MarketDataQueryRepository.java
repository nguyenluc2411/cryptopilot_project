package com.cryptopilot.market.repository;

import com.cryptopilot.market.model.DerivativesInputs;
import com.cryptopilot.market.model.FundingSettlement;
import com.cryptopilot.market.model.FuturesPriceSnapshot;
import com.cryptopilot.market.model.LongShortReading;
import com.cryptopilot.market.model.OpenInterestReading;
import com.cryptopilot.market.model.SpotSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the stored market snapshots for the market data API (UC-09): the Spot and futures snapshot hypertables and
 * the settled funding rates. Read-only; the writers are NSF-03 ({@link MarketSnapshotRepository}) and NSF-04
 * ({@link FuturesMetricsRepository}).
 *
 * <h2>Each value is read from the row that holds it</h2>
 *
 * <p>A {@code futures_market_data} row can hold the mark price columns (NSF-03, every minute), the metric columns
 * (NSF-04, every five minutes), or both (D-45). So the latest price is the latest row whose mark price is present,
 * and the latest open interest is the latest row whose open interest is present — two different rows in general,
 * each answered with its own instant. A row that has only metrics is never read as a price, and a series returns
 * only the instants that hold its value: nothing is filled in between readings.
 *
 * <p>Rule: UC-09; NSF-03, NSF-04; BR-10, BR-11; D-45, D-46; TECHNICAL_DESIGN 6.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MarketDataQueryRepository {

    private final JdbcClient sql;

    /** The latest Spot snapshot of a pair. */
    public Optional<SpotSnapshot> latestSpot(UUID pairId) {
        return sql.sql("""
                        select snapshot_time, last_price, best_bid_price, best_ask_price, high_price_24h, low_price_24h,
                               price_change_percent_24h, base_volume_24h, quote_volume_24h
                          from spot_market_data
                         where pair_id = ?
                         order by snapshot_time desc
                         limit 1""")
                .param(pairId)
                .query((row, index) -> new SpotSnapshot(
                        instant(row, "snapshot_time"),
                        row.getBigDecimal("last_price"),
                        row.getBigDecimal("best_bid_price"),
                        row.getBigDecimal("best_ask_price"),
                        row.getBigDecimal("high_price_24h"),
                        row.getBigDecimal("low_price_24h"),
                        row.getBigDecimal("price_change_percent_24h"),
                        row.getBigDecimal("base_volume_24h"),
                        row.getBigDecimal("quote_volume_24h")))
                .optional();
    }

    /** The latest futures row that holds a mark price; a row with metrics only is skipped. */
    public Optional<FuturesPriceSnapshot> latestFuturesPrice(UUID pairId) {
        return sql.sql("""
                        select snapshot_time, mark_price, index_price, funding_rate, next_funding_time
                          from futures_market_data
                         where pair_id = ? and mark_price is not null
                         order by snapshot_time desc
                         limit 1""")
                .param(pairId)
                .query((row, index) -> new FuturesPriceSnapshot(
                        instant(row, "snapshot_time"),
                        row.getBigDecimal("mark_price"),
                        row.getBigDecimal("index_price"),
                        row.getBigDecimal("funding_rate"),
                        instant(row, "next_funding_time")))
                .optional();
    }

    /** The latest open interest reading of a pair. */
    public Optional<OpenInterestReading> latestOpenInterest(UUID pairId) {
        return openInterest(pairId, null, null, 1, "desc").stream().findFirst();
    }

    /** The latest long/short account ratio reading of a pair. */
    public Optional<LongShortReading> latestLongShortRatio(UUID pairId) {
        return longShortRatios(pairId, null, null, 1, "desc").stream().findFirst();
    }

    /** The latest settled funding rate of a pair. */
    public Optional<FundingSettlement> latestFundingSettlement(UUID pairId) {
        return settlements(pairId, null, null, 1, "desc").stream().findFirst();
    }

    /** The open interest readings in {@code [from, to)}, oldest first, only the instants that hold one. */
    public List<OpenInterestReading> openInterestBetween(UUID pairId, Instant from, Instant to) {
        return openInterest(pairId, from, to, Integer.MAX_VALUE, "asc");
    }

    /** The long/short account ratio readings in {@code [from, to)}, oldest first, only the instants that hold one. */
    public List<LongShortReading> longShortRatiosBetween(UUID pairId, Instant from, Instant to) {
        return longShortRatios(pairId, from, to, Integer.MAX_VALUE, "asc");
    }

    /** The settled funding rates in {@code [from, to)}, oldest first. */
    public List<FundingSettlement> settlementsBetween(UUID pairId, Instant from, Instant to) {
        return settlements(pairId, from, to, Integer.MAX_VALUE, "asc");
    }

    /** The earliest stored open interest reading, the start of the period the system can chart (BR-10). */
    public Optional<Instant> earliestOpenInterest(UUID pairId) {
        return earliest(
                "select min(snapshot_time) from futures_market_data where pair_id = ? and open_interest is not null",
                pairId);
    }

    /** The earliest stored long/short account ratio reading (BR-10). */
    public Optional<Instant> earliestLongShortRatio(UUID pairId) {
        return earliest(
                "select min(snapshot_time) from futures_market_data where pair_id = ? and long_short_ratio is not null",
                pairId);
    }

    /** The earliest stored settlement. */
    public Optional<Instant> earliestFundingSettlement(UUID pairId) {
        return earliest("select min(funding_time) from funding_rate_history where pair_id = ?", pairId);
    }

    private List<OpenInterestReading> openInterest(UUID pairId, Instant from, Instant to, int limit, String order) {
        return sql.sql("select snapshot_time, open_interest, open_interest_value from futures_market_data"
                        + " where pair_id = ? and open_interest is not null"
                        + " and snapshot_time >= ? and snapshot_time < ?"
                        + " order by snapshot_time " + order + " limit ?")
                .params(pairId, lower(from), upper(to), limit)
                .query((row, index) -> new OpenInterestReading(
                        instant(row, "snapshot_time"),
                        row.getBigDecimal("open_interest"),
                        row.getBigDecimal("open_interest_value")))
                .list();
    }

    private List<LongShortReading> longShortRatios(UUID pairId, Instant from, Instant to, int limit, String order) {
        return sql.sql("select snapshot_time, long_short_ratio, long_account_ratio, short_account_ratio"
                        + " from futures_market_data"
                        + " where pair_id = ? and long_short_ratio is not null"
                        + " and snapshot_time >= ? and snapshot_time < ?"
                        + " order by snapshot_time " + order + " limit ?")
                .params(pairId, lower(from), upper(to), limit)
                .query((row, index) -> new LongShortReading(
                        instant(row, "snapshot_time"),
                        row.getBigDecimal("long_short_ratio"),
                        row.getBigDecimal("long_account_ratio"),
                        row.getBigDecimal("short_account_ratio")))
                .list();
    }

    private List<FundingSettlement> settlements(UUID pairId, Instant from, Instant to, int limit, String order) {
        return sql.sql("select funding_time, funding_rate, mark_price from funding_rate_history"
                        + " where pair_id = ? and funding_time >= ? and funding_time < ?"
                        + " order by funding_time " + order + " limit ?")
                .params(pairId, lower(from), upper(to), limit)
                .query((row, index) -> new FundingSettlement(
                        instant(row, "funding_time"),
                        row.getBigDecimal("funding_rate"),
                        row.getBigDecimal("mark_price")))
                .list();
    }

    /**
     * The Futures data the derivatives component of a candle closing at {@code at} reads: the predicted funding
     * rate, and the mark price and open interest changes over the hour before {@code at}. Each value is the latest
     * one stored at or before its instant; a change is {@code null} when either end has none.
     *
     * <p>Rule: BR-13; TECHNICAL_DESIGN 7.4.
     */
    public DerivativesInputs derivativesAt(UUID pairId, Instant at) {
        Timestamp now = Timestamp.from(at);
        Timestamp hourAgo = Timestamp.from(at.minus(Duration.ofHours(1)));
        return sql.sql("""
                        select (select funding_rate from futures_market_data
                                 where pair_id = :pair and snapshot_time <= :now and funding_rate is not null
                                 order by snapshot_time desc limit 1) as funding_rate,
                               (select mark_price from futures_market_data
                                 where pair_id = :pair and snapshot_time <= :now and mark_price is not null
                                 order by snapshot_time desc limit 1)
                             - (select mark_price from futures_market_data
                                 where pair_id = :pair and snapshot_time <= :ago and mark_price is not null
                                 order by snapshot_time desc limit 1) as price_change,
                               (select open_interest from futures_market_data
                                 where pair_id = :pair and snapshot_time <= :now and open_interest is not null
                                 order by snapshot_time desc limit 1)
                             - (select open_interest from futures_market_data
                                 where pair_id = :pair and snapshot_time <= :ago and open_interest is not null
                                 order by snapshot_time desc limit 1) as open_interest_change""")
                .param("pair", pairId)
                .param("now", now)
                .param("ago", hourAgo)
                .query((row, index) -> new DerivativesInputs(
                        row.getBigDecimal("funding_rate"),
                        row.getBigDecimal("price_change"),
                        row.getBigDecimal("open_interest_change")))
                .single();
    }

    private Optional<Instant> earliest(String query, UUID pairId) {
        return sql.sql(query).param(pairId).query(Instant.class).optional();
    }

    private static Timestamp lower(Instant from) {
        return Timestamp.from(from == null ? Instant.EPOCH : from);
    }

    private static Timestamp upper(Instant to) {
        return to == null ? Timestamp.valueOf("9999-12-31 00:00:00") : Timestamp.from(to);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
