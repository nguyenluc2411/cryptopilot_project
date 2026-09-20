package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.support.TestcontainersConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What V2 turned the four time-series tables into, read back out of TimescaleDB's own catalogue.
 *
 * <p>The interesting assertions are the retention ones. A retention policy drops whole chunks and
 * cannot look at a column, so the settled funding rates had to leave {@code futures_market_data}
 * before a ninety-day policy could be put on it. The tests below state both halves of that: the
 * snapshot tables expire, and {@code funding_rate_history} has no policy at all and is not even a
 * hypertable, because a row there is worth more than the storage it costs — deleting one changes a
 * closed position's realized profit and loss.
 *
 * <p>Rule: NSF-17 (retention), BR-37 (funding in realized P/L), SRS 3.1.5; D-18.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class HypertableTest {

    @Autowired
    private JdbcClient jdbc;

    @ParameterizedTest(name = "{0} is a hypertable chunked every {1}")
    @CsvSource({
        "ohlcv, 30 days",
        "technical_indicator, 30 days",
        "spot_market_data, 7 days",
        "futures_market_data, 7 days"
    })
    void timeSeriesTable_isAHypertableWithTheDocumentedChunkInterval(String table, String interval) {
        String actual = jdbc.sql("""
                        select d.time_interval::text
                          from timescaledb_information.dimensions d
                         where d.hypertable_schema = 'public'
                           and d.hypertable_name = ?
                           and d.dimension_number = 1""").param(table).query(String.class).single();

        assertThat(actual).isEqualTo(interval);
    }

    @Test
    void theFourTimeSeriesTables_areTheOnlyHypertables() {
        List<String> hypertables = jdbc.sql("""
                        select hypertable_name
                          from timescaledb_information.hypertables
                         where hypertable_schema = 'public'
                         order by hypertable_name""").query(String.class).list();

        assertThat(hypertables)
                .containsExactly("futures_market_data", "ohlcv", "spot_market_data", "technical_indicator");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ohlcv", "technical_indicator"})
    void candleTable_isCompressedAfterSixtyDays(String table) {
        List<String> policies = jdbc.sql("""
                        select config ->> 'compress_after'
                          from timescaledb_information.jobs
                         where proc_name = 'policy_compression'
                           and hypertable_schema = 'public'
                           and hypertable_name = ?""").param(table).query(String.class).list();

        assertThat(policies).containsExactly("60 days");
    }

    @ParameterizedTest
    @CsvSource({"spot_market_data, 7 days", "futures_market_data, 90 days"})
    void snapshotTable_isDroppedAfterItsRetentionWindow(String table, String window) {
        List<String> policies = jdbc.sql("""
                        select config ->> 'drop_after'
                          from timescaledb_information.jobs
                         where proc_name = 'policy_retention'
                           and hypertable_schema = 'public'
                           and hypertable_name = ?""").param(table).query(String.class).list();

        assertThat(policies).containsExactly(window);
    }

    @Test
    void onlyTheTwoSnapshotTables_haveARetentionPolicy() {
        List<String> retained = jdbc.sql("""
                        select hypertable_name
                          from timescaledb_information.jobs
                         where proc_name = 'policy_retention'
                           and hypertable_schema = 'public'
                         order by hypertable_name""").query(String.class).list();

        assertThat(retained)
                .as("candles, indicators and funding history are kept for ever")
                .containsExactly("futures_market_data", "spot_market_data");
    }

    @Test
    void fundingRateHistory_isAPlainTableWithNoRetentionPolicy() {
        Integer hypertable = jdbc.sql("""
                        select count(*)
                          from timescaledb_information.hypertables
                         where hypertable_schema = 'public' and hypertable_name = 'funding_rate_history'""").query(Integer.class).single();
        Integer jobs = jdbc.sql("""
                        select count(*)
                          from timescaledb_information.jobs
                         where hypertable_schema = 'public' and hypertable_name = 'funding_rate_history'""").query(Integer.class).single();

        assertThat(hypertable)
                .as("about three rows per pair per day needs no chunking")
                .isZero();
        assertThat(jobs)
                .as("a settled funding rate is needed for as long as the journal keeps the trade")
                .isZero();
    }

    @Test
    void futuresMarketData_noLongerCarriesASnapshotType() {
        List<String> columns = jdbc.sql("""
                        select column_name
                          from information_schema.columns
                         where table_schema = 'public' and table_name = 'futures_market_data'
                         order by column_name""").query(String.class).list();

        assertThat(columns)
                .as("every row left in the table is a periodic snapshot, so nothing tells kinds apart")
                .doesNotContain("snapshot_type");
    }

    /**
     * The point of the split, stated as a fact about the schema rather than about a policy run:
     * the table a retention policy would empty no longer holds anything the P/L needs, and the
     * table the P/L reads is not reachable by any policy.
     *
     * <p>This is the storage half of BR-37 and nothing more. That a closed position's realized
     * profit and loss actually includes funding is the rule itself, and it belongs to T-045; what
     * is proved here is that the numbers it will need cannot be deleted first.
     */
    @Test
    void BR37_settledFundingRates_areNotReachableByAnyRetentionPolicy() {
        Integer reachable = jdbc.sql("""
                        select count(*)
                          from timescaledb_information.jobs j
                         where j.proc_name = 'policy_retention'
                           and j.hypertable_schema = 'public'
                           and j.hypertable_name in (
                               select c.table_name
                                 from information_schema.columns c
                                where c.table_schema = 'public'
                                  and c.column_name = 'funding_rate'
                                  and c.table_name = 'funding_rate_history')""").query(Integer.class).single();

        assertThat(reachable).isZero();
    }
}
