package com.cryptopilot.market.repository;

import com.cryptopilot.market.calculator.FundingTimes;
import com.cryptopilot.market.client.FundingRate;
import com.cryptopilot.market.client.LongShortRatio;
import com.cryptopilot.market.client.OpenInterestStatistic;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What NSF-04 writes: the open interest and long/short account ratio columns of {@code futures_market_data}, and
 * the settled funding rates of {@code funding_rate_history}.
 *
 * <h2>Sharing the snapshot row with NSF-03</h2>
 *
 * <p>{@code futures_market_data} has one row per pair and instant, key {@code (pair_id, snapshot_time)}. NSF-03
 * writes the mark price columns every minute with {@code ON CONFLICT DO NOTHING}; NSF-04 writes the metric
 * columns every five minutes at the instant the exchange stamped them. Each metric is an upsert that sets its
 * own columns and nothing else: into NSF-03's row when there is one, as a new row with the mark price columns
 * {@code null} when there is none. No value is ever copied into another minute's row — the four minutes
 * between two readings keep their metric columns {@code null}, because nothing was measured then.
 *
 * <p>NSF-04 only writes instants before the current minute (see the service), by which time NSF-03 has normally
 * written that minute's row. Should NSF-04 still create the row first, NSF-03 fills its own empty columns on the
 * conflict and leaves these alone ({@link MarketSnapshotRepository#insertFutures}).
 *
 * <h2>Settled funding rates</h2>
 *
 * <p>One row per pair and settlement instant — the source's instant normalized by {@link FundingTimes} — with
 * {@code ON CONFLICT DO NOTHING} on {@code (pair_id, funding_time)}: a settlement read twice, or stamped a few
 * milliseconds differently, is stored once, and never changed. No retention reaches the table
 * (TECHNICAL_DESIGN 6), because a closed position's profit and loss is recomputed from it (BR-37).
 *
 * <p>Rule: NSF-04; BR-10, BR-11, BR-37; TECHNICAL_DESIGN 6 and 7.1 step 8.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesMetricsRepository {

    private final JdbcClient sql;

    /** The latest instant at or after {@code notBefore} with an open interest stored for the pair. */
    public Optional<Instant> latestOpenInterest(UUID pairId, Instant notBefore) {
        return latest(pairId, notBefore, "open_interest");
    }

    /** The latest instant at or after {@code notBefore} with a long/short account ratio stored for the pair. */
    public Optional<Instant> latestLongShortRatio(UUID pairId, Instant notBefore) {
        return latest(pairId, notBefore, "long_short_ratio");
    }

    /** Writes the open interest readings of a pair; answers how many were written. */
    public int upsertOpenInterest(UUID pairId, List<OpenInterestStatistic> readings) {
        int written = 0;
        for (OpenInterestStatistic reading : readings) {
            written += sql.sql("""
                            insert into futures_market_data (pair_id, snapshot_time, open_interest, open_interest_value)
                            values (?, ?, ?, ?)
                            on conflict (pair_id, snapshot_time) do update
                               set open_interest = excluded.open_interest,
                                   open_interest_value = excluded.open_interest_value""")
                    .params(
                            pairId,
                            Timestamp.from(reading.timestamp()),
                            reading.openInterest(),
                            reading.openInterestValue())
                    .update();
        }
        return written;
    }

    /** Writes the long/short account ratio readings of a pair; answers how many were written. */
    public int upsertLongShortRatio(UUID pairId, List<LongShortRatio> readings) {
        int written = 0;
        for (LongShortRatio reading : readings) {
            written += sql.sql("""
                            insert into futures_market_data (pair_id, snapshot_time, long_short_ratio,
                                                             long_account_ratio, short_account_ratio)
                            values (?, ?, ?, ?, ?)
                            on conflict (pair_id, snapshot_time) do update
                               set long_short_ratio = excluded.long_short_ratio,
                                   long_account_ratio = excluded.long_account_ratio,
                                   short_account_ratio = excluded.short_account_ratio""")
                    .params(
                            pairId,
                            Timestamp.from(reading.timestamp()),
                            reading.longShortRatio(),
                            reading.longAccount(),
                            reading.shortAccount())
                    .update();
        }
        return written;
    }

    /** The latest settlement stored for the pair. */
    public Optional<Instant> latestFundingTime(UUID pairId) {
        return sql.sql("select max(funding_time) from funding_rate_history where pair_id = ?")
                .param(pairId)
                .query(Instant.class)
                .optional();
    }

    /**
     * Stores settled funding rates of a pair; answers how many were new. Each is stored at its instant normalized by
     * {@link FundingTimes#normalize}, the one rule for this table, so two stamps of one settlement are one row.
     * Every rate must carry its mark price, which the column requires and the profit and loss multiplies by.
     */
    public int insertFundingRates(UUID pairId, List<FundingRate> rates) {
        int inserted = 0;
        for (FundingRate rate : rates) {
            inserted += sql.sql("""
                            insert into funding_rate_history (pair_id, funding_time, funding_rate, mark_price)
                            values (?, ?, ?, ?)
                            on conflict (pair_id, funding_time) do nothing""")
                    .params(
                            pairId,
                            Timestamp.from(FundingTimes.normalize(rate.fundingTime())),
                            rate.fundingRate(),
                            rate.markPrice())
                    .update();
        }
        return inserted;
    }

    private Optional<Instant> latest(UUID pairId, Instant notBefore, String column) {
        return sql.sql("select max(snapshot_time) from futures_market_data"
                        + " where pair_id = ? and snapshot_time >= ? and " + column + " is not null")
                .params(pairId, Timestamp.from(notBefore))
                .query(Instant.class)
                .optional();
    }
}
