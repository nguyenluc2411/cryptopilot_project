package com.cryptopilot.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.client.BinanceClientProperties;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.FundingRate;
import com.cryptopilot.market.client.InMemoryBinanceBans;
import com.cryptopilot.market.client.LongShortRatio;
import com.cryptopilot.market.client.OpenInterestStatistic;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StubExchange;
import com.cryptopilot.market.client.StubExchange.Answer;
import com.cryptopilot.market.config.FuturesMetricsProperties;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.FuturesMetricsRepository;
import com.cryptopilot.market.repository.MarketSnapshotRepository;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * NSF-04 against a stand-in that answers the futures history, funding and mark price endpoints like Binance,
 * and the migrated TimescaleDB schema: the 5-minute metrics land on their own instants in the snapshot rows
 * NSF-03 shares, nothing is carried into another minute, nothing is written twice or from a refused call, and
 * each settlement is found from the source's next funding time and interval — never an assumed one.
 *
 * <p>Not {@code @Transactional}: every page commits on its own, and the tests look at what committed. The run
 * happens 90 seconds after 10:05, as scheduled; the depth is shortened to an hour so a case needs a few pages.
 *
 * <p>Rule: NSF-04; BR-07, BR-10, BR-11, BR-37; TECHNICAL_DESIGN 5.4, 7.1 step 8 and 7.1.2.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class FuturesMetricsServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:06:30Z");

    private static final String OPEN_INTEREST = "/futures/data/openInterestHist";
    private static final String LONG_SHORT = "/futures/data/globalLongShortAccountRatio";
    private static final String FUNDING_RATE = "/fapi/v1/fundingRate";
    private static final String FUNDING_INFO = "/fapi/v1/fundingInfo";
    private static final String PREMIUM_INDEX = "/fapi/v1/premiumIndex";

    @Autowired
    private CryptoPairRepository pairs;

    @Autowired
    private FuturesMetricsRepository metrics;

    @Autowired
    private MarketSnapshotRepository snapshots;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcClient sql;

    private final MutableTestClock clock = new MutableTestClock(NOW);
    private final SyntheticFuturesMetrics source = new SyntheticFuturesMetrics(Instant.parse("2026-09-24T10:05:00Z"));
    private final LatestMarketData latest = new LatestMarketData();

    private MarketTestData data;
    private StubExchange exchange;
    private BinanceRestClient client;

    @BeforeEach
    void startTheStandIn() throws Exception {
        data = new MarketTestData(sql, NOW);
        exchange = new StubExchange();
        exchange.respond(OPEN_INTEREST, source::openInterestHist);
        exchange.respond(LONG_SHORT, source::longShortRatio);
        exchange.respond(FUNDING_RATE, source::fundingRate);
        exchange.on(FUNDING_INFO, Answer.ok("[]"));
        client = clientFor(exchange.baseUrl());
    }

    @AfterEach
    void emptyTheTables() {
        client.close();
        exchange.close();
        sql.sql("delete from funding_rate_history").update();
        data.clear();
    }

    // ------------------------------------------------------------------ open interest and long/short ratio

    /**
     * BR-10: the first collection of a pair stores the source's history as the system's own — every 5-minute
     * instant from the depth to the last period the exchange has published, in pages that send both ends of the
     * range, every decimal kept.
     */
    @Test
    void BR10_theFirstCollection_storesTheSourcesHistoryAsTheSystemsOwn() {
        UUID btc = futuresPair("BTCUSDT");

        MetricsRun run = service(5, 50).collectMetrics();

        assertThat(instantsWith(btc, "open_interest"))
                .as("09:10 to 10:05, twelve readings")
                .hasSize(12)
                .first()
                .isEqualTo(Instant.parse("2026-09-24T09:10:00Z"));
        assertThat(instantsWith(btc, "open_interest")).last().isEqualTo(Instant.parse("2026-09-24T10:05:00Z"));
        assertThat(instantsWith(btc, "long_short_ratio")).hasSize(12);
        assertThat(run.openInterestRows()).isEqualTo(12);
        assertThat(run.longShortRows()).isEqualTo(12);
        assertThat(run.requests()).as("three pages of five per metric").isEqualTo(6);
        assertThat(run.stoppedAtRequestLimit()).isFalse();
        assertThat(exchange.requests().stream()
                        .filter(uri -> uri.getPath().equals(OPEN_INTEREST))
                        .map(URI::getQuery))
                .allSatisfy(query -> assertThat(query).contains("period=5m", "startTime=", "endTime=", "limit=5"));

        Instant at = Instant.parse("2026-09-24T10:05:00Z");
        Map<String, Object> row = row(btc, at);
        assertThat(row.get("open_interest")).isEqualTo(new BigDecimal(SyntheticFuturesMetrics.openInterest(at)));
        assertThat(row.get("open_interest_value"))
                .isEqualTo(new BigDecimal(SyntheticFuturesMetrics.openInterestValue(at)));
        assertThat(row.get("long_short_ratio")).isEqualTo(new BigDecimal("1.23460000"));
        assertThat(row.get("long_account_ratio")).isEqualTo(new BigDecimal("0.55250000"));
        assertThat(row.get("short_account_ratio")).isEqualTo(new BigDecimal("0.44750000"));
        assertThat(row.get("mark_price")).as("no NSF-03 row there: stays empty").isNull();
    }

    /**
     * NSF-03's row at a 5-minute instant receives the metrics and keeps its own values; the minute rows between
     * two readings keep their metric columns empty — nothing is carried forward.
     */
    @Test
    void NSF04_theMetricsJoinNsf03sRow_andTheMinutesBetweenStayEmpty() {
        UUID btc = futuresPair("BTCUSDT");
        Instant fourMinutes = Instant.parse("2026-09-24T10:04:00Z");
        Instant fiveMinutes = Instant.parse("2026-09-24T10:05:00Z");
        nsf03Row(btc, fourMinutes);
        nsf03Row(btc, fiveMinutes);

        service(500, 50).collectMetrics();

        Map<String, Object> joined = row(btc, fiveMinutes);
        assertThat(joined.get("mark_price")).isEqualTo(new BigDecimal("63055.123456780000"));
        assertThat(joined.get("funding_rate")).isEqualTo(new BigDecimal("0.00010000"));
        assertThat(joined.get("open_interest")).isNotNull();
        assertThat(joined.get("long_short_ratio")).isNotNull();
        Map<String, Object> between = row(btc, fourMinutes);
        assertThat(between.get("mark_price")).isNotNull();
        assertThat(between.get("open_interest")).as("no forward fill").isNull();
        assertThat(between.get("open_interest_value")).isNull();
        assertThat(between.get("long_short_ratio")).isNull();
        assertThat(rowCount(btc))
                .as("twelve 5-minute rows, one of them NSF-03's, plus 10:04")
                .isEqualTo(13);
    }

    /** Run again at the same moment: nothing is asked, nothing is written twice. */
    @Test
    void NSF04_aRepeatedRun_writesNoSecondRow() {
        UUID btc = futuresPair("BTCUSDT");
        FuturesMetricsService service = service(5, 50);
        service.collectMetrics();

        MetricsRun again = service.collectMetrics();

        assertThat(again.requests()).isZero();
        assertThat(again.openInterestRows()).isZero();
        assertThat(rowCount(btc)).isEqualTo(12);
    }

    /**
     * NSF-04 created the row first: NSF-03's snapshot of the same instant fills its own empty columns and leaves
     * the metrics as they were; a second NSF-03 write of that instant changes nothing — the first one wins.
     */
    @Test
    void NSF03_aRowNsf04CreatedFirst_receivesThePriceAndKeepsTheMetrics() {
        UUID btc = futuresPair("BTCUSDT");
        Instant at = Instant.parse("2026-09-24T10:05:00Z");
        metrics.upsertOpenInterest(
                btc, List.of(new OpenInterestStatistic("BTCUSDT", new BigDecimal("1.5"), new BigDecimal("2.5"), at)));
        metrics.upsertLongShortRatio(
                btc,
                List.of(new LongShortRatio(
                        "BTCUSDT", new BigDecimal("1.2346"), new BigDecimal("0.5525"), new BigDecimal("0.4475"), at)));

        int filled = snapshots.insertFutures(at, Map.of(btc, mark("BTCUSDT", "63055.12345678", at)));
        int again = snapshots.insertFutures(at, Map.of(btc, mark("BTCUSDT", "1.00000000", at)));

        assertThat(filled).isOne();
        assertThat(again).isZero();
        Map<String, Object> row = row(btc, at);
        assertThat(row.get("mark_price")).isEqualTo(new BigDecimal("63055.123456780000"));
        assertThat(row.get("index_price")).isEqualTo(new BigDecimal("63050.000000000000"));
        assertThat(row.get("funding_rate")).isEqualTo(new BigDecimal("0.00010000"));
        assertThat(row.get("next_funding_time")).isNotNull();
        assertThat(row.get("open_interest")).as("NSF-04's value kept").isEqualTo(new BigDecimal("1.500000000000"));
        assertThat(row.get("open_interest_value")).isEqualTo(new BigDecimal("2.50000000"));
        assertThat(row.get("long_short_ratio")).isEqualTo(new BigDecimal("1.23460000"));
        assertThat(rowCount(btc)).isOne();
    }

    /** The same reading written twice by the upsert is one row with the same values. */
    @Test
    void NSF04_theSameReadingTwice_isOneRow() {
        UUID btc = futuresPair("BTCUSDT");
        Instant at = Instant.parse("2026-09-24T10:05:00Z");
        var reading = new OpenInterestStatistic("BTCUSDT", new BigDecimal("1.5"), new BigDecimal("2.5"), at);

        metrics.upsertOpenInterest(btc, List.of(reading));
        metrics.upsertOpenInterest(btc, List.of(reading));

        assertThat(rowCount(btc)).isOne();
        assertThat(row(btc, at).get("open_interest")).isEqualTo(new BigDecimal("1.500000000000"));
    }

    /** The current minute is left for the next run: the last instant written is before it. */
    @Test
    void NSF04_theCurrentMinute_isNotWritten() {
        UUID btc = futuresPair("BTCUSDT");
        clock.set(Instant.parse("2026-09-24T10:05:20Z"));

        service(500, 50).collectMetrics();

        assertThat(instantsWith(btc, "open_interest")).last().isEqualTo(Instant.parse("2026-09-24T10:00:00Z"));
    }

    /** A period the exchange has not published is asked for again by the next run, and written then. */
    @Test
    void NSF04_aPeriodNotYetPublished_isWrittenByTheNextRun() {
        UUID btc = futuresPair("BTCUSDT");
        source.publishedUntil(Instant.parse("2026-09-24T10:00:00Z"));
        FuturesMetricsService service = service(500, 50);
        service.collectMetrics();
        assertThat(instantsWith(btc, "open_interest")).last().isEqualTo(Instant.parse("2026-09-24T10:00:00Z"));

        source.publishedUntil(Instant.parse("2026-09-24T10:05:00Z"));
        service.collectMetrics();

        assertThat(instantsWith(btc, "open_interest")).last().isEqualTo(Instant.parse("2026-09-24T10:05:00Z"));
        assertThat(instantsWith(btc, "long_short_ratio")).hasSize(12);
    }

    /** A rate limit stops the run with the exchange's retry instant, and not a single row is written. */
    @Test
    void NSF04_aRateLimit_writesNoRowAndPropagates() {
        UUID btc = futuresPair("BTCUSDT");
        exchange.on(OPEN_INTEREST, Answer.status(429).withHeader("Retry-After", "30"));

        assertThatExceptionOfType(BinanceClientException.class)
                .isThrownBy(() -> service(500, 50).collectMetrics())
                .satisfies(refusal -> {
                    assertThat(refusal.kind()).isEqualTo(BinanceClientException.Kind.RATE_LIMITED);
                    assertThat(refusal.retryAt()).contains(NOW.plusSeconds(30));
                });
        assertThat(rowCount(btc)).isZero();
    }

    /** An outage stops the run without a retry instant, and writes no empty row. */
    @Test
    void NSF04_anOutage_writesNoEmptyRow() {
        UUID btc = futuresPair("BTCUSDT");
        exchange.on(OPEN_INTEREST, Answer.status(503));

        assertThatExceptionOfType(BinanceClientException.class)
                .isThrownBy(() -> service(500, 50).collectMetrics())
                .satisfies(refusal -> assertThat(refusal.kind()).isEqualTo(BinanceClientException.Kind.UNAVAILABLE));
        assertThat(rowCount(btc)).isZero();
    }

    /** A series the exchange rejects is a defect of that series; the others are collected. */
    @Test
    void NSF04_aRejectedSeries_isADefect_andTheOthersAreCollected() {
        UUID btc = futuresPair("BTCUSDT");
        UUID eth = futuresPair("ETHUSDT");
        exchange.respond(
                OPEN_INTEREST,
                uri -> uri.getQuery().contains("symbol=ETHUSDT") ? Answer.status(400) : source.openInterestHist(uri));

        MetricsRun run = service(500, 50).collectMetrics();

        assertThat(run.defects()).containsExactly("ETHUSDT open interest");
        assertThat(instantsWith(btc, "open_interest")).hasSize(12);
        assertThat(instantsWith(eth, "open_interest")).isEmpty();
        assertThat(instantsWith(eth, "long_short_ratio")).hasSize(12);
    }

    /** The request limit stops a run; the next continues from what was stored and finishes. */
    @Test
    void NSF04_theRequestLimit_stopsTheRun_andTheNextContinues() {
        UUID btc = futuresPair("BTCUSDT");
        FuturesMetricsService service = service(5, 2);

        MetricsRun first = service.collectMetrics();

        assertThat(first.stoppedAtRequestLimit()).isTrue();
        assertThat(first.requests()).isEqualTo(2);
        assertThat(instantsWith(btc, "open_interest")).hasSize(10);
        assertThat(instantsWith(btc, "long_short_ratio")).isEmpty();

        service.collectMetrics();
        MetricsRun third = service.collectMetrics();

        assertThat(third.stoppedAtRequestLimit()).isFalse();
        assertThat(instantsWith(btc, "open_interest")).hasSize(12);
        assertThat(instantsWith(btc, "long_short_ratio")).hasSize(12);
    }

    /** BR-07 and D-44: only pairs enabled on futures and trading there are collected. */
    @Test
    void BR07_onlyEnabledTradingFuturesPairs_areCollected() {
        data.pair("ETHUSDT", true, false, "TRADING", "TRADING", 1);
        data.pair("XRPUSDT", false, true, null, "NOT_TRADING", 2);

        MetricsRun run = service(500, 50).collectMetrics();

        assertThat(run.requests()).isZero();
        assertThat(exchange.requests()).isEmpty();
    }

    // ------------------------------------------------------------------ funding settlements

    /**
     * BR-11: pairs settling every 8, 4 and 1 hours, each interval and next funding time read from the source.
     * The 8-hour and 1-hour pairs have a settlement not yet stored and read it; the 4-hour pair is current and
     * is not asked.
     */
    @Test
    void BR11_eightFourAndOneHourPairs_areSettledFromTheSourceSchedule() {
        UUID btc = futuresPair("BTCUSDT");
        UUID lpt = futuresPair("LPTUSDT");
        UUID lsk = futuresPair("LSKUSDT");
        fundingInfo(Map.of("BTCUSDT", 8, "LPTUSDT", 4, "LSKUSDT", 1));
        markPrice(btc, "BTCUSDT", "2026-09-24T16:00:00Z", NOW.minusSeconds(1));
        markPrice(lpt, "LPTUSDT", "2026-09-24T12:00:00Z", NOW.minusSeconds(1));
        markPrice(lsk, "LSKUSDT", "2026-09-24T11:00:00Z", NOW.minusSeconds(1));
        stored(btc, "2026-09-24T00:00:00Z");
        stored(lpt, "2026-09-24T08:00:00Z");
        stored(lsk, "2026-09-24T09:00:00Z");
        source.settlements("BTCUSDT", epoch("2026-09-24T08:00:00.005Z") + ",0.00001422,84374.30000000");
        source.settlements("LPTUSDT", epoch("2026-09-24T08:00:00.001Z") + ",0.00005000,1.61036765");
        source.settlements("LSKUSDT", epoch("2026-09-24T10:00:00Z") + ",-0.00012345,0.52100000");

        SettlementRun run = service(500, 50).settleFunding();

        assertThat(run.pairsChecked()).isEqualTo(3);
        assertThat(run.pairsDue()).isEqualTo(2);
        assertThat(run.rowsInserted()).isEqualTo(2);
        assertThat(source.requests("LPTUSDT fundingRate"))
                .as("4 h pair is current")
                .isZero();
        assertThat(settlements(btc))
                .as("stored at the normalized instant")
                .containsExactly(Instant.parse("2026-09-24T00:00:00Z"), Instant.parse("2026-09-24T08:00:00Z"));
        assertThat(settlements(lsk)).last().isEqualTo(Instant.parse("2026-09-24T10:00:00Z"));
        Map<String, Object> charged = sql.sql(
                        "select * from funding_rate_history where pair_id = ? and funding_time = ?")
                .params(lsk, Timestamp.from(Instant.parse("2026-09-24T10:00:00Z")))
                .query()
                .singleRow();
        assertThat(charged.get("funding_rate")).isEqualTo(new BigDecimal("-0.00012345"));
        assertThat(charged.get("mark_price")).isEqualTo(new BigDecimal("0.521000000000"));
        assertThat(exchange.requests().stream().filter(uri -> uri.getPath().equals(FUNDING_RATE)))
                .extracting(URI::getQuery)
                .as("read from half a minute after the stored settlement")
                .contains("symbol=BTCUSDT&startTime=" + epoch("2026-09-24T00:00:30Z") + "&limit=1000");
    }

    /**
     * BR-11: a symbol the exchange lists no interval for is checked every run — here, where an assumed 8 hours
     * would have called it current — and never fitted with a default.
     */
    @Test
    void BR11_aSymbolMissingFromFundingInfo_isCheckedEveryRun_notAssumedEightHours() {
        UUID xrp = futuresPair("XRPUSDT");
        fundingInfo(Map.of("BTCUSDT", 8));
        markPrice(xrp, "XRPUSDT", "2026-09-24T16:00:00Z", NOW.minusSeconds(1));
        stored(xrp, "2026-09-24T08:00:00Z");
        source.settlements("XRPUSDT", epoch("2026-09-24T09:00:00Z") + ",0.00002000,0.55000000");

        FuturesMetricsService service = service(500, 50);
        SettlementRun first = service.settleFunding();
        SettlementRun second = service.settleFunding();

        assertThat(first.pairsDue()).isOne();
        assertThat(first.rowsInserted())
                .as("an hourly schedule an 8 h guess would have missed")
                .isOne();
        assertThat(second.pairsDue()).isOne();
        assertThat(second.rowsInserted()).isZero();
        assertThat(source.requests("XRPUSDT fundingRate")).isEqualTo(2);
    }

    /** A settlement read twice is stored once and never changed: a later reading of the same instant is ignored. */
    @Test
    void NSF04_aSettlementReadTwice_isStoredOnce() {
        UUID btc = futuresPair("BTCUSDT");
        markPrice(btc, "BTCUSDT", "2026-09-24T16:00:00Z", NOW.minusSeconds(1));
        source.settlements(
                "BTCUSDT",
                epoch("2026-09-24T00:00:00Z") + ",0.00004796,84472.24256522",
                epoch("2026-09-24T08:00:00.005Z") + ",0.00001422,84374.30000000");
        FuturesMetricsService service = service(500, 50);
        assertThat(service.settleFunding().rowsInserted()).isEqualTo(2);

        int again = metrics.insertFundingRates(
                btc,
                List.of(new FundingRate(
                        "BTCUSDT",
                        Instant.parse("2026-09-24T00:00:00Z"),
                        new BigDecimal("0.99999999"),
                        BigDecimal.ONE)));
        SettlementRun rerun = service.settleFunding();

        assertThat(again).isZero();
        assertThat(rerun.rowsInserted()).isZero();
        assertThat(settlements(btc)).hasSize(2);
        assertThat(sql.sql("select funding_rate from funding_rate_history where pair_id = ? and funding_time = ?")
                        .params(btc, Timestamp.from(Instant.parse("2026-09-24T00:00:00Z")))
                        .query(BigDecimal.class)
                        .single())
                .as("the first reading stays")
                .isEqualTo(new BigDecimal("0.00004796"));
    }

    /**
     * BR-37: one settlement the source stamps two ways ({@code ...600000} and {@code ...600005}) is one row, at the
     * normalized instant, within a page and through the repository directly.
     */
    @Test
    void BR37_oneSettlementStampedTwoWays_isOneRow() {
        UUID btc = futuresPair("BTCUSDT");
        markPrice(btc, "BTCUSDT", "2026-09-24T16:00:00Z", NOW.minusSeconds(1));
        source.settlements(
                "BTCUSDT",
                epoch("2026-09-24T08:00:00Z") + ",0.00001422,84374.30000000",
                epoch("2026-09-24T08:00:00.005Z") + ",0.00001422,84374.30000000");

        SettlementRun run = service(500, 50).settleFunding();
        int direct = metrics.insertFundingRates(
                btc,
                List.of(new FundingRate(
                        "BTCUSDT",
                        Instant.parse("2026-09-24T08:00:00.005Z"),
                        new BigDecimal("0.00001422"),
                        new BigDecimal("84374.30000000"))));

        assertThat(run.rowsInserted()).isOne();
        assertThat(direct).isZero();
        assertThat(settlements(btc)).containsExactly(Instant.parse("2026-09-24T08:00:00Z"));
    }

    /**
     * BR-11: a symbol whose interval changes from 8 to 4 hours between two runs, with the funding information
     * following the change. Every settlement is stored once, on time.
     */
    @Test
    void BR11_anIntervalChangeFromEightToFourHours_losesAndDoublesNothing() {
        UUID btc = futuresPair("BTCUSDT");
        stored(btc, "2026-09-24T00:00:00Z");
        stored(btc, "2026-09-24T08:00:00Z");
        FuturesMetricsService service = service(500, 50);

        fundingInfo(Map.of("BTCUSDT", 8));
        markPrice(btc, "BTCUSDT", "2026-09-24T16:00:00Z", NOW.minusSeconds(1));
        assertThat(service.settleFunding().pairsDue()).as("8 h, current").isZero();

        fundingInfo(Map.of("BTCUSDT", 4));
        source.settlements(
                "BTCUSDT",
                epoch("2026-09-24T08:00:00.005Z") + ",0.00001000,84000.00000000",
                epoch("2026-09-24T12:00:00.004Z") + ",0.00002000,84100.00000000");
        runAt(service, btc, "2026-09-24T12:01:30Z", "2026-09-24T16:00:00Z");
        assertThat(settlements(btc)).last().as("stored on time").isEqualTo(Instant.parse("2026-09-24T12:00:00Z"));

        source.settlements(
                "BTCUSDT",
                epoch("2026-09-24T08:00:00.005Z") + ",0.00001000,84000.00000000",
                epoch("2026-09-24T12:00:00.004Z") + ",0.00002000,84100.00000000",
                epoch("2026-09-24T16:00:00Z") + ",0.00003000,84200.00000000");
        runAt(service, btc, "2026-09-24T16:01:30Z", "2026-09-24T20:00:00Z");

        assertThat(settlements(btc))
                .containsExactly(
                        Instant.parse("2026-09-24T00:00:00Z"),
                        Instant.parse("2026-09-24T08:00:00Z"),
                        Instant.parse("2026-09-24T12:00:00Z"),
                        Instant.parse("2026-09-24T16:00:00Z"));
    }

    /**
     * BR-11: the same change while the funding information still says 8 hours. The 12:00 settlement is found one
     * interval later, with the 16:00 one: late, but neither lost nor doubled, and no interval assumed.
     */
    @Test
    void BR11_anIntervalChangeTheFundingInfoLagsBehind_isCaughtUpWithoutLossOrDuplicate() {
        UUID btc = futuresPair("BTCUSDT");
        stored(btc, "2026-09-24T08:00:00Z");
        fundingInfo(Map.of("BTCUSDT", 8));
        FuturesMetricsService service = service(500, 50);
        source.settlements(
                "BTCUSDT",
                epoch("2026-09-24T08:00:00.005Z") + ",0.00001000,84000.00000000",
                epoch("2026-09-24T12:00:00.004Z") + ",0.00002000,84100.00000000");

        runAt(service, btc, "2026-09-24T12:01:30Z", "2026-09-24T16:00:00Z");
        assertThat(settlements(btc)).as("not due by the stale 8 h yet").hasSize(1);

        source.settlements(
                "BTCUSDT",
                epoch("2026-09-24T08:00:00.005Z") + ",0.00001000,84000.00000000",
                epoch("2026-09-24T12:00:00.004Z") + ",0.00002000,84100.00000000",
                epoch("2026-09-24T16:00:00Z") + ",0.00003000,84200.00000000");
        runAt(service, btc, "2026-09-24T16:01:30Z", "2026-09-24T20:00:00Z");
        runAt(service, btc, "2026-09-24T16:06:30Z", "2026-09-24T20:00:00Z");

        assertThat(settlements(btc))
                .containsExactly(
                        Instant.parse("2026-09-24T08:00:00Z"),
                        Instant.parse("2026-09-24T12:00:00Z"),
                        Instant.parse("2026-09-24T16:00:00Z"));
    }

    /** A settlement without a mark price cannot be charged (BR-37) and is not stored; the others are. */
    @Test
    void BR37_aSettlementWithoutAMarkPrice_isNotStored() {
        UUID btc = futuresPair("BTCUSDT");
        markPrice(btc, "BTCUSDT", "2026-09-24T16:00:00Z", NOW.minusSeconds(1));
        source.settlements(
                "BTCUSDT",
                epoch("2026-09-24T00:00:00Z") + ",0.00004796,",
                epoch("2026-09-24T08:00:00Z") + ",0.00001422,84374.30000000");

        SettlementRun run = service(500, 50).settleFunding();

        assertThat(run.rowsInserted()).isOne();
        assertThat(run.withoutMarkPrice()).isOne();
        assertThat(settlements(btc)).containsExactly(Instant.parse("2026-09-24T08:00:00Z"));
    }

    /** A settlement the source dates after now is not stored yet. */
    @Test
    void NSF04_aSettlementAfterNow_isNotStored() {
        UUID btc = futuresPair("BTCUSDT");
        markPrice(btc, "BTCUSDT", "2026-09-24T16:00:00Z", NOW.minusSeconds(1));
        source.settlements(
                "BTCUSDT",
                epoch("2026-09-24T08:00:00Z") + ",0.00001422,84374.30000000",
                epoch("2026-09-24T16:00:00Z") + ",0.00001000,84000.00000000");

        service(500, 50).settleFunding();

        assertThat(settlements(btc)).containsExactly(Instant.parse("2026-09-24T08:00:00Z"));
    }

    /**
     * BR-11: a fresh streamed next funding time is used as it is; a stale one is read again from the source's
     * premium index.
     */
    @Test
    void BR11_aStaleStream_readsTheNextFundingTimeFromThePremiumIndex() {
        UUID btc = futuresPair("BTCUSDT");
        UUID eth = futuresPair("ETHUSDT");
        fundingInfo(Map.of("BTCUSDT", 8, "ETHUSDT", 8));
        stored(btc, "2026-09-24T08:00:00Z");
        stored(eth, "2026-09-24T08:00:00Z");
        markPrice(btc, "BTCUSDT", "2026-09-24T16:00:00Z", NOW.minusSeconds(1));
        markPrice(eth, "ETHUSDT", "2026-09-24T16:00:00Z", NOW.minus(Duration.ofMinutes(3)));
        exchange.on(PREMIUM_INDEX, Answer.ok("""
                {"symbol":"ETHUSDT","markPrice":"4000.1","indexPrice":"4000.0","estimatedSettlePrice":"4000.0",
                 "lastFundingRate":"0.0001","interestRate":"0.0001","nextFundingTime":1790294400000,
                 "time":1790292781000}"""));

        SettlementRun run = service(500, 50).settleFunding();

        assertThat(exchange.requests().stream().filter(uri -> uri.getPath().equals(PREMIUM_INDEX)))
                .extracting(URI::getQuery)
                .containsExactly("symbol=ETHUSDT");
        assertThat(run.pairsDue()).isZero();
    }

    /** No settlement stored: read from the settlement depth, forward across as many full pages as there are. */
    @Test
    void NSF04_aPairWithNoSettlementStored_readsItsHistoryAcrossPages() {
        UUID lsk = futuresPair("LSKUSDT");
        markPrice(lsk, "LSKUSDT", "2026-09-24T11:00:00Z", NOW.minusSeconds(1));
        Instant first =
                NOW.minus(Duration.ofDays(50)).plus(Duration.ofMinutes(53)).plusSeconds(30);
        source.hourlySettlements("LSKUSDT", first, 1200);

        SettlementRun run = service(500, 50, Duration.ofDays(50)).settleFunding();

        assertThat(run.rowsInserted()).isEqualTo(1200);
        assertThat(source.requests("LSKUSDT fundingRate")).as("1000, then 200").isEqualTo(2);
        assertThat(exchange.requests().stream().filter(uri -> uri.getPath().equals(FUNDING_RATE)))
                .extracting(URI::getQuery)
                .first()
                .isEqualTo("symbol=LSKUSDT&startTime="
                        + NOW.minus(Duration.ofDays(50)).toEpochMilli() + "&limit=1000");
    }

    /** A rate-limited settlement read stops the run with its retry instant and stores nothing. */
    @Test
    void NSF04_aRateLimitedSettlementRead_storesNothingAndPropagates() {
        UUID btc = futuresPair("BTCUSDT");
        markPrice(btc, "BTCUSDT", "2026-09-24T16:00:00Z", NOW.minusSeconds(1));
        exchange.on(FUNDING_RATE, Answer.status(429).withHeader("Retry-After", "60"));

        assertThatExceptionOfType(BinanceClientException.class)
                .isThrownBy(() -> service(500, 50).settleFunding())
                .satisfies(refusal -> assertThat(refusal.retryAt()).contains(NOW.plusSeconds(60)));
        assertThat(settlements(btc)).isEmpty();
    }

    /** A pair whose settlements the exchange rejects is a defect; the others are stored. */
    @Test
    void NSF04_aRejectedPair_isADefect_andTheOthersAreStored() {
        UUID btc = futuresPair("BTCUSDT");
        UUID eth = futuresPair("ETHUSDT");
        markPrice(btc, "BTCUSDT", "2026-09-24T16:00:00Z", NOW.minusSeconds(1));
        markPrice(eth, "ETHUSDT", "2026-09-24T16:00:00Z", NOW.minusSeconds(1));
        source.settlements("BTCUSDT", epoch("2026-09-24T08:00:00Z") + ",0.00001422,84374.30000000");
        exchange.respond(
                FUNDING_RATE,
                uri -> uri.getQuery().contains("symbol=ETHUSDT") ? Answer.status(400) : source.fundingRate(uri));

        SettlementRun run = service(500, 50).settleFunding();

        assertThat(run.defects()).containsExactly("ETHUSDT");
        assertThat(settlements(btc)).hasSize(1);
    }

    /** Unreadable funding settings state no interval: every pair is checked, and the defect is reported. */
    @Test
    void BR11_unreadableFundingInfo_checksEveryPair() {
        UUID btc = futuresPair("BTCUSDT");
        markPrice(btc, "BTCUSDT", "2026-09-24T16:00:00Z", NOW.minusSeconds(1));
        stored(btc, "2026-09-24T08:00:00Z");
        exchange.on(FUNDING_INFO, Answer.ok("{\"not\":\"a list\"}"));

        SettlementRun run = service(500, 50).settleFunding();

        assertThat(run.defects()).containsExactly("fundingInfo");
        assertThat(run.pairsDue()).isOne();
    }

    /** Funding settings refused for a rate limit stop the run like any other refusal. */
    @Test
    void NSF04_aRateLimitedFundingInfo_propagates() {
        futuresPair("BTCUSDT");
        exchange.on(FUNDING_INFO, Answer.status(429).withHeader("Retry-After", "10"));

        assertThatExceptionOfType(BinanceClientException.class)
                .isThrownBy(() -> service(500, 50).settleFunding())
                .satisfies(refusal -> assertThat(refusal.kind()).isEqualTo(BinanceClientException.Kind.RATE_LIMITED));
    }

    /** No futures pair: nothing is asked at all. */
    @Test
    void NSF04_noFuturesPair_asksNothing() {
        SettlementRun run = service(500, 50).settleFunding();

        assertThat(run).isEqualTo(new SettlementRun(0, 0, 0, 0, List.of()));
        assertThat(exchange.requests()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private FuturesMetricsService service(int pageSize, int maxRequests) {
        return service(pageSize, maxRequests, Duration.ofDays(30));
    }

    private FuturesMetricsService service(int pageSize, int maxRequests, Duration settlementDepth) {
        return new FuturesMetricsService(
                client,
                pairs,
                metrics,
                latest,
                new FuturesMetricsProperties(
                        true,
                        "30 1/5 * * * *",
                        ZoneId.of("UTC"),
                        Duration.ofHours(1),
                        pageSize,
                        maxRequests,
                        settlementDepth,
                        Duration.ofMinutes(2)),
                transactions,
                clock);
    }

    private UUID futuresPair(String symbol) {
        return data.pair(symbol, false, true, null, "TRADING", 0);
    }

    private void fundingInfo(Map<String, Integer> hours) {
        StringBuilder body = new StringBuilder("[");
        hours.forEach((symbol, h) -> body.append(body.length() > 1 ? "," : "")
                .append("{\"symbol\":\"")
                .append(symbol)
                .append("\",\"adjustedFundingRateCap\":\"0.02\",\"adjustedFundingRateFloor\":\"-0.02\",")
                .append("\"fundingIntervalHours\":")
                .append(h)
                .append(",\"disclaimer\":false,\"updateTime\":null}"));
        exchange.on(FUNDING_INFO, Answer.ok(body.append("]").toString()));
    }

    private void markPrice(UUID pairId, String symbol, String nextFundingTime, Instant eventTime) {
        latest.recordMarkPrice(
                pairId,
                new MarkPriceMessage(
                        symbol,
                        new BigDecimal("100.5"),
                        new BigDecimal("100.4"),
                        new BigDecimal("0.0001"),
                        Instant.parse(nextFundingTime),
                        eventTime));
    }

    private void runAt(FuturesMetricsService service, UUID pairId, String now, String nextFundingTime) {
        clock.set(Instant.parse(now));
        markPrice(pairId, "BTCUSDT", nextFundingTime, clock.instant().minusSeconds(1));
        service.settleFunding();
    }

    private static MarkPriceMessage mark(String symbol, String markPrice, Instant at) {
        return new MarkPriceMessage(
                symbol,
                new BigDecimal(markPrice),
                new BigDecimal("63050.00000000"),
                new BigDecimal("0.00010000"),
                Instant.parse("2026-09-24T16:00:00Z"),
                at);
    }

    private void stored(UUID pairId, String fundingTime) {
        sql.sql(
                        "insert into funding_rate_history (pair_id, funding_time, funding_rate, mark_price) values (?, ?, ?, ?)")
                .params(pairId, Timestamp.from(Instant.parse(fundingTime)), new BigDecimal("0.0001"), BigDecimal.TEN)
                .update();
    }

    private void nsf03Row(UUID pairId, Instant at) {
        sql.sql("""
                        insert into futures_market_data (pair_id, snapshot_time, mark_price, index_price, funding_rate,
                                                         next_funding_time)
                        values (?, ?, ?, ?, ?, ?)""")
                .params(
                        pairId,
                        Timestamp.from(at),
                        new BigDecimal("63055.12345678"),
                        new BigDecimal("63050.00000000"),
                        new BigDecimal("0.00010000"),
                        Timestamp.from(Instant.parse("2026-09-24T16:00:00Z")))
                .update();
    }

    private List<Instant> instantsWith(UUID pairId, String column) {
        return sql.sql("select snapshot_time from futures_market_data where pair_id = ? and " + column
                        + " is not null order by snapshot_time")
                .param(pairId)
                .query(Instant.class)
                .list();
    }

    private List<Instant> settlements(UUID pairId) {
        return sql.sql("select funding_time from funding_rate_history where pair_id = ? order by funding_time")
                .param(pairId)
                .query(Instant.class)
                .list();
    }

    private Map<String, Object> row(UUID pairId, Instant at) {
        return sql.sql("select * from futures_market_data where pair_id = ? and snapshot_time = ?")
                .params(pairId, Timestamp.from(at))
                .query()
                .singleRow();
    }

    private long rowCount(UUID pairId) {
        return sql.sql("select count(*) from futures_market_data where pair_id = ?")
                .param(pairId)
                .query(Long.class)
                .single();
    }

    private static long epoch(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    private BinanceRestClient clientFor(URI base) {
        return new BinanceRestClient(
                new BinanceClientProperties(
                        new BinanceClientProperties.Venue(base, 6000),
                        new BinanceClientProperties.Venue(base, 2400),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(2),
                        80,
                        Duration.ofMinutes(2),
                        new BinanceClientProperties.Retry(
                                0, Duration.ofMillis(1), 1.0, Duration.ofMillis(1), Duration.ZERO),
                        new BinanceClientProperties.CircuitBreaker(50, Duration.ofSeconds(30))),
                clock,
                JsonMapper.builder().build(),
                new InMemoryBinanceBans());
    }
}
