package com.cryptopilot.market.repository;

import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The stored figures the market list of coins derives from: closes of the stored candles at a past instant, the closes
 * of a week for the small chart, and the open interest of the Futures pairs.
 *
 * <p>Every candle stored is closed (BR-08), so a close at or before an instant is a price the pair really traded at
 * then. Reads only; the candles and the open interest are written by NSF-02 and NSF-04.
 *
 * <p>Rule: SRS 3.3.1; BR-08.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class CoinMarketRepository {

    private final JdbcClient sql;

    /**
     * The close of the latest stored candle of a series that closed at or before {@code at}, if one closed within
     * {@code notBefore} and {@code at}.
     */
    public Optional<BigDecimal> closeAt(
            UUID pairId, MarketType market, String timeframe, Instant at, Instant notBefore) {
        return sql.sql("""
                        select close_price
                          from ohlcv
                         where pair_id = ? and market_type = ? and timeframe = ?
                           and close_time <= ? and close_time > ?
                         order by open_time desc
                         limit 1""")
                .params(pairId, market.name(), timeframe, Timestamp.from(at), Timestamp.from(notBefore))
                .query(BigDecimal.class)
                .optional();
    }

    /** The closes of a series' candles opened at or after {@code from}, oldest first. */
    public List<BigDecimal> closesSince(UUID pairId, MarketType market, String timeframe, Instant from) {
        return sql.sql("""
                        select close_price
                          from ohlcv
                         where pair_id = ? and market_type = ? and timeframe = ? and open_time >= ?
                         order by open_time""")
                .params(pairId, market.name(), timeframe, Timestamp.from(from))
                .query(BigDecimal.class)
                .list();
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
     * The summed open interest of the pairs at each hour from {@code from} to {@code to}: per pair the latest sample
     * taken within {@code window} before the hour (NSF-04 samples every five minutes). An hour without any sample is
     * left out.
     */
    public List<OpenInterestPoint> openInterestHourly(
            Collection<UUID> pairIds, Instant from, Instant to, java.time.Duration window) {
        if (pairIds.isEmpty()) {
            return List.of();
        }
        return sql.sql("""
                        select b.at, sum(s.open_interest_value) as value_usd, count(*) as pairs
                          from generate_series(cast(:from as timestamptz), cast(:to as timestamptz),
                                               interval '1 hour') as b(at)
                          cross join lateral (
                              select distinct on (d.pair_id) d.open_interest_value
                                from futures_market_data d
                               where d.pair_id in (:pairs)
                                 and d.snapshot_time <= b.at
                                 and d.snapshot_time > b.at - cast(:window as interval)
                                 and d.open_interest_value is not null
                               order by d.pair_id, d.snapshot_time desc) as s
                         group by b.at
                         order by b.at""")
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("pairs", pairIds)
                .param("window", window.toSeconds() + " seconds")
                .query((row, index) -> new OpenInterestPoint(
                        row.getTimestamp("at").toInstant(), row.getBigDecimal("value_usd"), row.getInt("pairs")))
                .list();
    }
}
