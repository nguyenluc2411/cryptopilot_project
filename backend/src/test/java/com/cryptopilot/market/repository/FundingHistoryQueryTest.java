package com.cryptopilot.market.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.FundingAnnouncement;
import com.cryptopilot.market.FundingHistory;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.SettledFunding;
import com.cryptopilot.market.SnapshotGap;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the funding of a position is computed from: the settlements of {@code funding_rate_history} in
 * {@code (after, upTo]}, and the settlement instants the mark price snapshots of {@code futures_market_data}
 * announced in the same period, and the announcements, snapshot gaps and earlier settlements the completeness check
 * reads, aggregated by the database.
 *
 * <p>Rule: BR-11, BR-37; A-05, A-12.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class FundingHistoryQueryTest {

    private static final Instant T8 = Instant.parse("2026-10-20T08:00:00Z");
    private static final Instant T16 = Instant.parse("2026-10-20T16:00:00Z");
    private static final Instant T24 = Instant.parse("2026-10-21T00:00:00Z");

    @Autowired
    private MarketApi market;

    @Autowired
    private JdbcClient sql;

    private UUID pair;

    @BeforeEach
    void aFuturesPair() {
        pair = new MarketTestData(sql, Instant.now()).pair("FHQUSDT", false, true, null, "TRADING", 0);
    }

    @Test
    void BR37_settlementsAreReadAfterTheEntryAndUpToTheExit_withTheirMarkPrice() {
        settlement(T8, "0.0001", "101.5");
        settlement(T16, "-0.0002", "99");
        settlement(T24, "0.0003", "100");

        FundingHistory history = market.fundingHistory(pair, T8, T16);

        assertThat(history.settlements()).singleElement().satisfies(settled -> {
            assertThat(settled.fundingTime()).isEqualTo(T16);
            assertThat(settled.fundingRate()).isEqualByComparingTo("-0.0002");
            assertThat(settled.markPrice()).isEqualByComparingTo("99");
        });
    }

    /**
     * Every minute's snapshot announces the next settlement; the instants in the period come back once each,
     * normalized to the minute as a stored settlement is, including the one a snapshot just before the period announced.
     */
    @Test
    void A05_theAnnouncedSettlementsOfThePeriod_comeBackOnceEachAndNormalized() {
        Instant entry = T8.minusSeconds(30);
        snapshot(entry.minusSeconds(60), T8.plusMillis(5));
        snapshot(T8.minusSeconds(10), T8);
        snapshot(T8.plusSeconds(60), T16);
        snapshot(T16.minusSeconds(60), T16);
        snapshot(T16.plusSeconds(60), T24);

        FundingHistory history = market.fundingHistory(pair, entry, T16);

        assertThat(history.scheduledTimes()).containsExactly(T8, T16);
        assertThat(history.settlements()).isEmpty();
    }

    @Test
    void A05_aPairWithoutFuturesData_hasAnEmptyHistory() {
        FundingHistory history = market.fundingHistory(UUID.randomUUID(), T8, T24);

        assertThat(history.settlements()).isEmpty();
        assertThat(history.scheduledTimes()).isEmpty();
    }

    @Test
    void A05_settlementsAndAnnouncements_togetherShowTheMissingOne() {
        snapshot(T8.minusSeconds(60), T8);
        snapshot(T8.plusSeconds(60), T16);
        settlement(T8, "0.0001", "101");

        FundingHistory history = market.fundingHistory(pair, T8.minusSeconds(120), T16.plusSeconds(60));

        assertThat(history.settlements())
                .extracting(SettledFunding::fundingTime)
                .containsExactly(T8);
        assertThat(history.scheduledTimes()).containsExactly(T8, T16);
    }

    /**
     * The review outage in the database: snapshots every minute until 06:59 and again from 17:00, one row of NSF-04
     * alone at 12:00 (no next funding time) inside the outage. The database returns one row per announced instant
     * with its first and last snapshot, and only the gaps, not the snapshots: before the first snapshot of the
     * window, 06:59 to 17:00 (the NSF-04 row does not close it), and after the last one to the end.
     */
    @Test
    void A05_announcementsAndGaps_areComputedInTheDatabase_fromTheMarkPriceSnapshotsOnly() {
        Instant entry = T8.minus(Duration.ofHours(8));
        Instant exit = entry.plus(Duration.ofHours(20));
        snapshots(T8.minus(Duration.ofMinutes(70)), T8.minus(Duration.ofMinutes(61)), T8);
        snapshot(T8.minus(Duration.ofMinutes(65)).plusSeconds(30), T8.plusMillis(5));
        nsf04Row(T8.plus(Duration.ofHours(4)));
        snapshots(T16.plusSeconds(3600), T16.plusSeconds(3600 + 600), T24);

        FundingHistory history = market.fundingHistory(pair, entry, exit);

        assertThat(history.announcements())
                .containsExactly(
                        new FundingAnnouncement(T8, T8.minus(Duration.ofMinutes(70)), T8.minus(Duration.ofMinutes(61))),
                        new FundingAnnouncement(T24, T16.plusSeconds(3600), T16.plusSeconds(3600 + 600)));
        assertThat(history.snapshotGaps())
                .containsExactly(
                        new SnapshotGap(entry.minus(Duration.ofDays(1)), T8.minus(Duration.ofMinutes(70))),
                        new SnapshotGap(T8.minus(Duration.ofMinutes(61)), T16.plusSeconds(3600)),
                        new SnapshotGap(T16.plusSeconds(3600 + 600), exit));
        assertThat(history.scheduledTimes()).containsExactly(T8);
    }

    /** A window without a single mark price snapshot is one gap from its start to its end. */
    @Test
    void A05_aWindowWithoutSnapshots_isOneGap() {
        nsf04Row(T8.plusSeconds(60));

        FundingHistory history = market.fundingHistory(pair, T8, T16);

        assertThat(history.announcements()).isEmpty();
        assertThat(history.snapshotGaps()).containsExactly(new SnapshotGap(T8.minus(Duration.ofDays(1)), T16));
    }

    /** Snapshots every minute leave no gap inside the window. */
    @Test
    void A05_snapshotsEveryMinute_leaveNoGap() {
        Instant entry = T8.minusSeconds(600);
        snapshots(entry.minus(Duration.ofDays(1)), T8.plusSeconds(600), T16);

        FundingHistory history = market.fundingHistory(pair, entry, T8.plusSeconds(600));

        assertThat(history.snapshotGaps()).isEmpty();
        assertThat(history.announcements()).singleElement().satisfies(a -> assertThat(a.fundingTime())
                .isEqualTo(T16));
    }

    /** The settlements stored in the look-back day before the period come back as instants only, for the interval. */
    @Test
    void A05_theSettlementsOfTheLookBackDay_comeBackForTheInterval() {
        settlement(T8.minus(Duration.ofDays(2)), "0.0001", "100");
        settlement(T16.minus(Duration.ofDays(1)), "0.0001", "100");
        settlement(T24.minus(Duration.ofDays(1)), "0.0001", "100");
        settlement(T8, "0.0001", "101");
        settlement(T16, "0.0001", "101");

        FundingHistory history = market.fundingHistory(pair, T8, T16);

        assertThat(history.earlierSettlementTimes())
                .containsExactly(T16.minus(Duration.ofDays(1)), T24.minus(Duration.ofDays(1)), T8);
        assertThat(history.settlements())
                .extracting(SettledFunding::fundingTime)
                .containsExactly(T16);
    }

    /** One snapshot per minute in {@code [from, to]}, each announcing the same settlement. */
    private void snapshots(Instant from, Instant to, Instant nextFundingTime) {
        for (Instant at = from; !at.isAfter(to); at = at.plusSeconds(60)) {
            snapshot(at, nextFundingTime);
        }
    }

    /** A row NSF-04 created for its metrics, without NSF-03's mark price columns. */
    private void nsf04Row(Instant at) {
        sql.sql("insert into futures_market_data (pair_id, snapshot_time, open_interest) values (?, ?, 1000)")
                .params(pair, Timestamp.from(at))
                .update();
    }

    private void settlement(Instant time, String rate, String mark) {
        sql.sql(
                        "insert into funding_rate_history (pair_id, funding_time, funding_rate, mark_price) values (?, ?, ?, ?)")
                .params(pair, Timestamp.from(time), new BigDecimal(rate), new BigDecimal(mark))
                .update();
    }

    private void snapshot(Instant at, Instant nextFundingTime) {
        sql.sql("""
                        insert into futures_market_data (pair_id, snapshot_time, mark_price, index_price, funding_rate,
                                                         next_funding_time)
                        values (?, ?, 100, 100, 0.0001, ?)""")
                .params(pair, Timestamp.from(at), Timestamp.from(nextFundingTime))
                .update();
    }
}
