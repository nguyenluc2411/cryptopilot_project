package com.cryptopilot.market.repository;

import com.cryptopilot.market.FundingAnnouncement;
import com.cryptopilot.market.FundingHistory;
import com.cryptopilot.market.OpenInterestSample;
import com.cryptopilot.market.SettledFunding;
import com.cryptopilot.market.SnapshotGap;
import com.cryptopilot.market.calculator.FundingTimes;
import com.cryptopilot.market.client.FundingRate;
import com.cryptopilot.market.client.LongShortRatio;
import com.cryptopilot.market.client.OpenInterestStatistic;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
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

    /**
     * How far before an interval the snapshots announcing its first settlement are read, and the window in which
     * the funding interval of the pair is looked for: well above the longest gap NSF-03 is expected to leave and the
     * longest funding interval, and no assumption about the interval itself.
     */
    static final Duration ANNOUNCEMENT_LOOKBACK = Duration.ofDays(1);

    /**
     * The spacing of NSF-03's snapshots, whose instants are truncated to the minute: two snapshots further apart
     * have at least one missing between them.
     */
    static final Duration SNAPSHOT_SPACING = Duration.ofMinutes(1);

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

    /** The latest open interest reading at or before {@code at}, in contracts; empty when none is stored. */
    public Optional<OpenInterestSample> openInterestAtOrBefore(UUID pairId, Instant at) {
        return sql.sql("""
                        select snapshot_time, open_interest from futures_market_data
                         where pair_id = ? and snapshot_time <= ? and open_interest is not null
                         order by snapshot_time desc
                         limit 1""")
                .params(pairId, Timestamp.from(at))
                .query((row, index) -> new OpenInterestSample(
                        row.getTimestamp("snapshot_time").toInstant(), row.getBigDecimal("open_interest")))
                .optional();
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

    /**
     * What the funding of a position open in {@code (after, upTo]} is computed and checked from (A-05): the stored
     * settlements of that interval with the instants the snapshots announced in it, and for the completeness check
     * the announcements and snapshot gaps of the window from {@link #ANNOUNCEMENT_LOOKBACK} before {@code after} to
     * {@code upTo}, and the settlements stored in that look-back.
     */
    public FundingHistory fundingHistory(UUID pairId, Instant after, Instant upTo) {
        Instant windowStart = after.minus(ANNOUNCEMENT_LOOKBACK);
        List<FundingAnnouncement> announcements = fundingAnnouncements(pairId, windowStart, upTo);
        List<Instant> scheduled = announcements.stream()
                .map(FundingAnnouncement::fundingTime)
                .filter(time -> time.isAfter(after) && !time.isAfter(upTo))
                .toList();
        return new FundingHistory(
                settledFunding(pairId, after, upTo),
                scheduled,
                announcements,
                snapshotGaps(pairId, windowStart, upTo),
                settledFunding(pairId, windowStart, after).stream()
                        .map(SettledFunding::fundingTime)
                        .toList());
    }

    /** The pair's stored funding settlements with an instant in {@code (after, upTo]}, oldest first (BR-37). */
    public List<SettledFunding> settledFunding(UUID pairId, Instant after, Instant upTo) {
        return sql.sql("""
                        select funding_time, funding_rate, mark_price from funding_rate_history
                         where pair_id = ? and funding_time > ? and funding_time <= ?
                         order by funding_time""")
                .params(pairId, Timestamp.from(after), Timestamp.from(upTo))
                .query((row, index) -> new SettledFunding(
                        row.getTimestamp("funding_time").toInstant(),
                        row.getBigDecimal("funding_rate"),
                        row.getBigDecimal("mark_price")))
                .list();
    }

    /**
     * Every settlement instant the pair's mark price snapshots of {@code [from, upTo]} announced as the next funding
     * time, with the first and last snapshot announcing it, oldest first. Every minute NSF-03 stores the next
     * settlement the exchange states, so this is how the funding times of a pair are known without assuming an
     * interval (BR-11). The database groups the snapshots, so only a few rows per settlement come back however long
     * the window is; the instants are then normalized by {@link FundingTimes#normalize} like a stored settlement,
     * and the stamps of one settlement merged.
     */
    List<FundingAnnouncement> fundingAnnouncements(UUID pairId, Instant from, Instant upTo) {
        Map<Instant, FundingAnnouncement> byTime = new TreeMap<>();
        sql.sql("""
                        select next_funding_time, min(snapshot_time) as first_seen, max(snapshot_time) as last_seen
                          from futures_market_data
                         where pair_id = ? and snapshot_time >= ? and snapshot_time <= ?
                           and next_funding_time is not null
                         group by next_funding_time""")
                .params(pairId, Timestamp.from(from), Timestamp.from(upTo))
                .query((row, index) -> new FundingAnnouncement(
                        FundingTimes.normalize(
                                row.getTimestamp("next_funding_time").toInstant()),
                        row.getTimestamp("first_seen").toInstant(),
                        row.getTimestamp("last_seen").toInstant()))
                .list()
                .forEach(announcement -> byTime.merge(
                        announcement.fundingTime(),
                        announcement,
                        (a, b) -> new FundingAnnouncement(
                                a.fundingTime(),
                                a.firstSeen().isBefore(b.firstSeen()) ? a.firstSeen() : b.firstSeen(),
                                a.lastSeen().isAfter(b.lastSeen()) ? a.lastSeen() : b.lastSeen())));
        return List.copyOf(byTime.values());
    }

    /**
     * The stretches of {@code [from, upTo]} where two consecutive mark price snapshots of the pair, or a snapshot and
     * an end of the window, are more than {@link #SNAPSHOT_SPACING} apart, oldest first. Computed in the database:
     * a window of a month holds some 44,000 snapshots and only the gaps come back. A window without any snapshot is
     * one gap.
     */
    List<SnapshotGap> snapshotGaps(UUID pairId, Instant from, Instant upTo) {
        return sql.sql("""
                        select previous_time, snapshot_time
                          from (select snapshot_time, lag(snapshot_time) over (order by snapshot_time) as previous_time
                                  from (select snapshot_time from futures_market_data
                                         where pair_id = ? and snapshot_time > ? and snapshot_time < ?
                                           and next_funding_time is not null
                                        union all select cast(? as timestamptz)
                                        union all select cast(? as timestamptz)) as series) as spaced
                         where snapshot_time - previous_time > ? * interval '1 second'
                         order by snapshot_time""")
                .params(
                        pairId,
                        Timestamp.from(from),
                        Timestamp.from(upTo),
                        Timestamp.from(from),
                        Timestamp.from(upTo),
                        SNAPSHOT_SPACING.toSeconds())
                .query((row, index) -> new SnapshotGap(
                        row.getTimestamp("previous_time").toInstant(),
                        row.getTimestamp("snapshot_time").toInstant()))
                .list();
    }

    private Optional<Instant> latest(UUID pairId, Instant notBefore, String column) {
        return sql.sql("select max(snapshot_time) from futures_market_data"
                        + " where pair_id = ? and snapshot_time >= ? and " + column + " is not null")
                .params(pairId, Timestamp.from(notBefore))
                .query(Instant.class)
                .optional();
    }
}
