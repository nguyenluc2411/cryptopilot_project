package com.cryptopilot.market.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.FundingHistory;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.SettledFunding;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
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
 * announced in the same period.
 *
 * <p>Rule: BR-37; A-05, A-12.
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
