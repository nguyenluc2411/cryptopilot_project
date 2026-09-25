package com.cryptopilot.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.client.BinanceClientProperties;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.InMemoryBinanceBans;
import com.cryptopilot.market.client.MarketInterval;
import com.cryptopilot.market.client.StubExchange;
import com.cryptopilot.market.client.StubExchange.Answer;
import com.cryptopilot.market.config.CandleBackfillProperties;
import com.cryptopilot.market.event.GapDetected;
import com.cryptopilot.market.model.BackfillRun;
import com.cryptopilot.market.model.GapFill;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.market.service.impl.CandleBackfillServiceImpl;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * NSF-02 against a klines endpoint that answers like Binance and the migrated TimescaleDB schema: which
 * series are filled, how far, across how many pages, that nothing is stored twice or before it has closed, and
 * what the backfill does at the weight share and when the exchange refuses.
 *
 * <p>Not {@code @Transactional}: every page commits on its own, and the tests look at what committed. The
 * candle, pair and coin tables are emptied after each test. The depths are shortened to hours and days so a
 * case needs a handful of pages; the rule under test does not depend on the length.
 *
 * <p>Rule: NSF-02; BR-07, BR-08; D-41, D-42; TECHNICAL_DESIGN 5.4 and 7.1.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class CandleBackfillServiceTest {

    /** Seven and a half minutes past the hour: every timeframe has a candle still forming. */
    private static final Instant NOW = Instant.parse("2026-09-24T10:07:30Z");

    private static final String KLINES = "/api/v3/klines";
    private static final String FUTURES_KLINES = "/fapi/v1/klines";

    @Autowired
    private CryptoPairRepository pairs;

    @Autowired
    private OhlcvRepository candles;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcClient sql;

    private final MutableTestClock clock = new MutableTestClock(NOW);

    private final SyntheticKlines exchangeKlines = new SyntheticKlines(NOW);

    private StubExchange exchange;

    private BinanceRestClient client;

    @BeforeEach
    void startTheStandIn() throws Exception {
        exchange = new StubExchange();
        exchange.respond(KLINES, exchangeKlines::answer);
        exchange.respond(FUTURES_KLINES, exchangeKlines::answer);
        client = clientFor(exchange.baseUrl());
    }

    @AfterEach
    void emptyTheTables() {
        client.close();
        exchange.close();
        sql.sql("delete from ohlcv").update();
        sql.sql("delete from crypto_pair").update();
        sql.sql("delete from coin").update();
    }

    /**
     * The first backfill of a pair: every stored timeframe from its depth to the last closed candle, across as
     * many pages as it takes, the forming candle left out, and a series shorter than a page read in one call.
     */
    @Test
    void NSF02_theFirstBackfill_fillsEveryTimeframeUpToTheLastClosedCandle() {
        UUID btc = pair("BTCUSDT", "TRADING", "TRADING");

        BackfillRun run = service(50, 20, depths()).backfill(MarketType.SPOT);

        assertThat(rows(btc, "15m")).isEqualTo(expectedClosed("15m", Duration.ofDays(1)));
        assertThat(rows(btc, "1h")).isEqualTo(expectedClosed("1h", Duration.ofDays(2)));
        assertThat(rows(btc, "4h")).isEqualTo(expectedClosed("4h", Duration.ofDays(5)));
        assertThat(rows(btc, "1d")).isEqualTo(expectedClosed("1d", Duration.ofDays(10)));
        assertThat(exchangeKlines.requests("BTCUSDT 15m"))
                .as("95 closed + 1 forming over pages of 50")
                .isEqualTo(2);
        assertThat(exchangeKlines.requests("BTCUSDT 1d"))
                .as("fewer than a page: one call")
                .isOne();
        assertThat(latestOpen(btc, "1h"))
                .as("BR-08: the forming candle is not stored")
                .isEqualTo(exchangeKlines.formingOpen(Duration.ofHours(1)).minus(Duration.ofHours(1)));
        assertThat(run.seriesCompleted()).isEqualTo(4);
        assertThat(run.paused()).isFalse();
        assertThat(rows(btc, "1h", MarketType.FUTURES))
                .as("the other market is its own run")
                .isZero();
    }

    /**
     * A page that is exactly full — every candle closed — does not end the series: the next call returns only
     * the forming candle, which is not stored, and ends it.
     */
    @Test
    void NSF02_aPageExactlyAtTheLimit_isFollowedByOneMoreCall() {
        UUID btc = pair("BTCUSDT", "TRADING", null);
        CandleBackfillProperties.Depth depth = new CandleBackfillProperties.Depth(
                Duration.ofHours(1), Duration.ofHours(40).plusMinutes(30), Duration.ofDays(1), Duration.ofDays(2));

        service(20, 20, depth).backfill(MarketType.SPOT);

        assertThat(rows(btc, "1h")).isEqualTo(40);
        assertThat(exchangeKlines.requests("BTCUSDT 1h"))
                .as("20 + 20 closed, then the forming one alone")
                .isEqualTo(3);
    }

    /** Running again with nothing new inserts nothing and asks for one page per series. */
    @Test
    void NSF02_aSecondRun_insertsNothingAndAsksOncePerSeries() {
        UUID btc = pair("BTCUSDT", "TRADING", null);
        service(50, 20, depths()).backfill(MarketType.SPOT);
        long before = rows(btc, "15m");

        BackfillRun again = service(50, 20, depths()).backfill(MarketType.SPOT);

        assertThat(again.rowsInserted()).isZero();
        assertThat(rows(btc, "15m")).isEqualTo(before);
        assertThat(exchangeKlines.requests("BTCUSDT 15m")).isEqualTo(3);
    }

    /**
     * An interrupted run resumes from the latest stored candle — the data is the checkpoint — and ends with
     * exactly the candles an uninterrupted run would have stored, none twice.
     */
    @Test
    void NSF02_anInterruptedRun_resumesFromTheLatestStoredCandle() {
        UUID btc = pair("BTCUSDT", "TRADING", null);
        AtomicInteger calls = new AtomicInteger();
        exchange.respond(KLINES, uri -> calls.incrementAndGet() > 1 ? Answer.status(503) : exchangeKlines.answer(uri));

        assertThatExceptionOfType(BinanceClientException.class)
                .isThrownBy(() -> service(50, 20, depths()).backfill(MarketType.SPOT))
                .satisfies(refusal -> assertThat(refusal.kind()).isEqualTo(BinanceClientException.Kind.UNAVAILABLE));
        assertThat(rows(btc, "15m")).as("the first page committed").isEqualTo(50);

        exchange.respond(KLINES, exchangeKlines::answer);
        service(50, 20, depths()).backfill(MarketType.SPOT);

        assertThat(rows(btc, "15m")).isEqualTo(expectedClosed("15m", Duration.ofDays(1)));
        assertThat(sql.sql("select count(distinct (timeframe, open_time)) = count(*) from ohlcv where pair_id = ?")
                        .param(btc)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    /** A pair listed after the depth's start begins at the first candle the exchange has. */
    @Test
    void NSF02_aPairListedLater_beginsAtItsFirstCandle() {
        UUID eth = pair("ETHUSDT", "TRADING", null);
        Instant listed = Instant.parse("2026-09-24T07:00:00Z");
        exchangeKlines.listed("ETHUSDT", listed);

        service(50, 20, depths()).backfill(MarketType.SPOT);

        assertThat(sql.sql("select min(open_time) from ohlcv where pair_id = ? and timeframe = '1h'")
                        .param(eth)
                        .query(Instant.class)
                        .single())
                .isEqualTo(listed);
        assertThat(rows(eth, "1h")).as("07:00, 08:00, 09:00; 10:00 is forming").isEqualTo(3);
    }

    /**
     * D-42: every pair TRADING on the market is backfilled, active or not; NOT_TRADING, DELISTED and a market
     * the pair is not listed on are skipped without a request.
     */
    @Test
    void NSF02_onlyPairsTradingOnTheMarket_areBackfilled() {
        pair("BTCUSDT", "TRADING", "TRADING");
        pair("ETHUSDT", "NOT_TRADING", "TRADING");
        pair("SOLUSDT", "DELISTED", null);
        pair("SHIBUSDT", null, null);

        service(50, 20, depths()).backfill(MarketType.SPOT);

        assertThat(exchangeKlines.symbolsRequested()).containsExactly("BTCUSDT");
        assertThat(sql.sql("select count(*) from crypto_pair where pair_status = 'ACTIVE'")
                        .query(Integer.class)
                        .single())
                .as("BR-07: backfilling never activates a pair")
                .isZero();
    }

    /**
     * The pacing rule: once the weight used this minute reaches the backfill's share (50 % of Spot's 6,000),
     * the run stops before the next page and reports the next minute as the instant to continue.
     */
    @Test
    void NSF02_atTheWeightShare_theRunPausesUntilTheNextMinute() {
        pair("BTCUSDT", "TRADING", null);
        exchangeKlines.usedWeight(3000);

        BackfillRun run = service(50, 20, depths()).backfill(MarketType.SPOT);

        assertThat(run.pausedUntil()).contains(Instant.parse("2026-09-24T10:08:00Z"));
        assertThat(exchangeKlines.requests())
                .as("the first page, then no more this minute")
                .isOne();
    }

    /** Below the share, the run carries on to the end. */
    @Test
    void NSF02_justBelowTheWeightShare_theRunCarriesOn() {
        pair("BTCUSDT", "TRADING", null);
        exchangeKlines.usedWeight(2999);

        assertThat(service(50, 20, depths()).backfill(MarketType.SPOT).paused()).isFalse();
    }

    /**
     * A REJECTED or MALFORMED answer is a defect of that series: it is skipped for this run and the other
     * pairs are still filled.
     */
    @Test
    void NSF02_aRejectedOrMalformedSeries_isSkippedAndTheRestCarryOn() {
        UUID btc = pair("BTCUSDT", "TRADING", null);
        pair("ETHUSDT", "TRADING", null);
        pair("SOLUSDT", "TRADING", null);
        exchange.respond(KLINES, uri -> {
            String query = uri.getQuery();
            if (query.contains("symbol=ETHUSDT")) {
                return Answer.status(400);
            }
            if (query.contains("symbol=SOLUSDT")) {
                return Answer.ok("{\"not\":\"klines\"}");
            }
            return exchangeKlines.answer(uri);
        });

        BackfillRun run = service(50, 20, depths()).backfill(MarketType.SPOT);

        assertThat(run.defects()).hasSize(8).allSatisfy(series -> assertThat(series)
                .containsAnyOf("ETHUSDT", "SOLUSDT"));
        assertThat(rows(btc, "1d")).isEqualTo(expectedClosed("1d", Duration.ofDays(10)));
    }

    /** Any other refusal stops the whole run and reaches the caller, which applies the caller contract. */
    @Test
    void NSF02_aRateLimit_stopsTheRunAndReachesTheCaller() {
        pair("BTCUSDT", "TRADING", null);
        exchange.on(KLINES, Answer.status(429).withHeader("Retry-After", "30"));

        assertThatExceptionOfType(BinanceClientException.class)
                .isThrownBy(() -> service(50, 20, depths()).backfill(MarketType.SPOT))
                .satisfies(refusal -> assertThat(refusal.retryAt()).contains(NOW.plusSeconds(30)));
    }

    /** TECHNICAL_DESIGN 5.4: the smallest and the longest decimals arrive in the columns unchanged. */
    @Test
    void TD54_pricesAndVolumes_keepTheirPrecision() {
        UUID btc = pair("BTCUSDT", "TRADING", null);

        service(50, 20, depths()).backfill(MarketType.SPOT);

        var row = sql.sql("select open_price, close_price, base_volume, trade_count from ohlcv"
                        + " where pair_id = ? and timeframe = '1d' limit 1")
                .param(btc)
                .query()
                .singleRow();
        assertThat((BigDecimal) row.get("open_price")).isEqualByComparingTo("0.00000001");
        assertThat((BigDecimal) row.get("close_price")).isEqualByComparingTo("123456.12345678");
        assertThat((BigDecimal) row.get("base_volume")).isEqualByComparingTo("12.00000001");
        assertThat(row.get("trade_count")).isEqualTo(1234);
    }

    /** The futures market is its own series, its own endpoint and its own page size. */
    @Test
    void NSF02_theFuturesMarket_isBackfilledThroughItsOwnEndpoint() {
        UUID btc = pair("BTCUSDT", null, "TRADING");

        service(50, 20, depths()).backfill(MarketType.FUTURES);

        assertThat(rows(btc, "15m", MarketType.FUTURES)).isEqualTo(expectedClosed("15m", Duration.ofDays(1)));
        assertThat(exchange.hits(FUTURES_KLINES)).isPositive();
        assertThat(exchange.hits(KLINES)).isZero();
    }

    /** NSF-03 found candles missing: exactly those are fetched, with an end time, and nothing after them. */
    @Test
    void NSF03_aGap_isFilledExactly_andNothingAfterIt() {
        UUID btc = pair("BTCUSDT", "TRADING", null);
        Instant from = Instant.parse("2026-09-24T01:00:00Z");
        Instant to = Instant.parse("2026-09-24T06:00:00Z");

        GapFill fill = service(50, 20, depths()).fillGap(gap(btc, MarketType.SPOT, "1h", from, to));

        assertThat(fill.rowsInserted()).isEqualTo(6);
        assertThat(fill.remaining()).isEmpty();
        assertThat(fill.pausedUntil()).isEmpty();
        assertThat(rows(btc, "1h")).isEqualTo(6);
        assertThat(latestOpen(btc, "1h")).isEqualTo(to);
        assertThat(exchange.requests().getFirst().getQuery())
                .contains("startTime=" + from.toEpochMilli())
                .contains(
                        "endTime=" + to.plus(Duration.ofHours(1)).minusMillis(1).toEpochMilli());
    }

    /** A gap is paged like a series: a long one takes several calls and still stops at its end. */
    @Test
    void NSF03_aLongGap_isFilledPageByPage() {
        UUID btc = pair("BTCUSDT", null, "TRADING");
        Instant from = Instant.parse("2026-09-23T00:00:00Z");
        Instant to = Instant.parse("2026-09-23T23:45:00Z");

        GapFill fill = service(50, 20, depths()).fillGap(gap(btc, MarketType.FUTURES, "15m", from, to));

        assertThat(fill.rowsInserted()).isEqualTo(96);
        assertThat(rows(btc, "15m", MarketType.FUTURES)).isEqualTo(96);
        assertThat(exchange.hits(FUTURES_KLINES))
                .as("96 candles over pages of 20")
                .isEqualTo(5);
    }

    /** Should the exchange ignore the end time, a candle after the gap is still not written by the backfill. */
    @Test
    void NSF03_candlesAfterTheGap_areNeverWrittenByIt() {
        UUID btc = pair("BTCUSDT", "TRADING", null);
        exchange.respond(
                KLINES, uri -> exchangeKlines.answer(URI.create(uri.toString().replaceAll("&endTime=\\d+", ""))));
        Instant to = Instant.parse("2026-09-24T06:00:00Z");

        service(50, 20, depths()).fillGap(gap(btc, MarketType.SPOT, "1h", Instant.parse("2026-09-24T05:00:00Z"), to));

        assertThat(rows(btc, "1h")).isEqualTo(2);
        assertThat(latestOpen(btc, "1h")).isEqualTo(to);
    }

    /** Stopped by the weight share, the gap reports what is left of it and when to continue. */
    @Test
    void NSF03_aGapStoppedAtTheWeightShare_reportsWhatIsLeft() {
        UUID btc = pair("BTCUSDT", "TRADING", null);
        exchangeKlines.usedWeight(3000);
        Instant from = Instant.parse("2026-09-24T00:00:00Z");
        Instant to = Instant.parse("2026-09-24T09:00:00Z");

        GapFill fill = service(4, 20, depths()).fillGap(gap(btc, MarketType.SPOT, "1h", from, to));

        assertThat(fill.rowsInserted()).isEqualTo(4);
        assertThat(fill.pausedUntil()).contains(Instant.parse("2026-09-24T10:08:00Z"));
        assertThat(fill.remaining()).hasValueSatisfying(rest -> {
            assertThat(rest.from()).isEqualTo(Instant.parse("2026-09-24T04:00:00Z"));
            assertThat(rest.to()).isEqualTo(to);
        });
    }

    /** Stopped before its first page, the whole gap is left. */
    @Test
    void NSF03_aGapStoppedBeforeItsFirstPage_isLeftWhole() {
        UUID btc = pair("BTCUSDT", "TRADING", null);
        exchangeKlines.usedWeight(3000);
        CandleBackfillService service = service(50, 20, depths());
        service.fillGap(gap(btc, MarketType.SPOT, "1d", Instant.parse("2026-09-20T00:00:00Z"), NOW));
        GapDetected second = gap(btc, MarketType.SPOT, "4h", Instant.parse("2026-09-23T00:00:00Z"), NOW);

        GapFill fill = service.fillGap(second);

        assertThat(fill.rowsInserted()).isZero();
        assertThat(fill.remaining()).contains(second);
    }

    /** A gap the exchange rejects is a defect: dropped, not retried. */
    @Test
    void NSF03_aRejectedGap_isDropped() {
        UUID btc = pair("BTCUSDT", "TRADING", null);
        exchange.on(KLINES, Answer.status(400));

        GapFill fill = service(50, 20, depths())
                .fillGap(gap(btc, MarketType.SPOT, "1h", Instant.parse("2026-09-24T01:00:00Z"), NOW));

        assertThat(fill).isEqualTo(new GapFill(0, java.util.Optional.empty(), java.util.Optional.empty()));
    }

    /** Any other refusal reaches the job, which keeps the gap. */
    @Test
    void NSF03_aRateLimitedGap_reachesTheCaller() {
        UUID btc = pair("BTCUSDT", "TRADING", null);
        exchange.on(KLINES, Answer.status(429).withHeader("Retry-After", "30"));

        assertThatExceptionOfType(BinanceClientException.class).isThrownBy(() -> service(50, 20, depths())
                .fillGap(gap(btc, MarketType.SPOT, "1h", Instant.parse("2026-09-24T01:00:00Z"), NOW)));
    }

    @Test
    void NSF03_aGapOfATimeframeNeverStored_isRefused() {
        UUID btc = pair("BTCUSDT", "TRADING", null);

        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> service(50, 20, depths()).fillGap(gap(btc, MarketType.SPOT, "2h", NOW, NOW)));
    }

    /** A-33: a hole in the middle of a stored series is found, as the gap between its neighbours. */
    @Test
    void A33_aHoleInsideAStoredSeries_isFound() {
        UUID btc = pair("BTCUSDT", "TRADING", "TRADING");
        CandleBackfillService service = service(50, 20, depths());
        service.backfill(MarketType.SPOT);
        service.backfill(MarketType.FUTURES);
        deleteOpenedBetween(btc, "1h", "2026-09-23T10:00:00Z", "2026-09-23T12:00:00Z");
        deleteOpenedBetween(btc, "15m", "2026-09-24T02:15:00Z", "2026-09-24T02:15:00Z");

        assertThat(service.storedGaps())
                .containsExactlyInAnyOrder(
                        new GapDetected(
                                btc,
                                "BTCUSDT",
                                MarketType.SPOT,
                                "1h",
                                Instant.parse("2026-09-23T10:00:00Z"),
                                Instant.parse("2026-09-23T12:00:00Z")),
                        new GapDetected(
                                btc,
                                "BTCUSDT",
                                MarketType.SPOT,
                                "15m",
                                Instant.parse("2026-09-24T02:15:00Z"),
                                Instant.parse("2026-09-24T02:15:00Z")));
    }

    /** A-33: complete series report nothing. */
    @Test
    void A33_aCleanSeries_reportsNothing() {
        pair("BTCUSDT", "TRADING", null);
        CandleBackfillService service = service(50, 20, depths());
        service.backfill(MarketType.SPOT);

        assertThat(service.storedGaps()).isEmpty();
    }

    /**
     * A-33: only holes inside the window are reported — one wholly before it is not, one that starts just
     * before its edge is — and only for pairs the backfill targets (D-42).
     */
    @Test
    void A33_theScan_keepsToItsWindowAndToTradingPairs() {
        UUID btc = pair("BTCUSDT", "TRADING", null);
        UUID eth = pair("ETHUSDT", "TRADING", null);
        CandleBackfillService service = service(50, 20, longDepths());
        service.backfill(MarketType.SPOT);
        deleteOpenedBetween(btc, "1d", "2026-09-10T00:00:00Z", "2026-09-10T00:00:00Z");
        deleteOpenedBetween(btc, "4h", "2026-09-17T08:00:00Z", "2026-09-17T12:00:00Z");
        deleteOpenedBetween(eth, "1h", "2026-09-23T10:00:00Z", "2026-09-23T10:00:00Z");
        sql.sql("update crypto_pair set spot_exchange_status = 'NOT_TRADING' where pair_id = ?")
                .param(eth)
                .update();

        assertThat(service.storedGaps())
                .singleElement()
                .isEqualTo(new GapDetected(
                        btc,
                        "BTCUSDT",
                        MarketType.SPOT,
                        "4h",
                        Instant.parse("2026-09-17T08:00:00Z"),
                        Instant.parse("2026-09-17T12:00:00Z")));
    }

    /** D-41: an empty series starts its depth back from now. */
    @Test
    void NSF02_seriesStart_isTheDepthBackFromNow() {
        assertThat(service(50, 20, depths()).seriesStart(MarketInterval.FOUR_HOURS, NOW))
                .isEqualTo(NOW.minus(Duration.ofDays(5)));
    }

    /** Depths long enough for a 7-day scan window to have history on both sides of its edge. */
    private static CandleBackfillProperties.Depth longDepths() {
        return new CandleBackfillProperties.Depth(
                Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(10), Duration.ofDays(20));
    }

    private void deleteOpenedBetween(UUID pair, String timeframe, String first, String last) {
        int deleted = sql.sql("""
                        delete from ohlcv where pair_id = ? and market_type = 'SPOT' and timeframe = ?
                           and open_time between ? and ?""")
                .params(
                        pair,
                        timeframe,
                        java.sql.Timestamp.from(Instant.parse(first)),
                        java.sql.Timestamp.from(Instant.parse(last)))
                .update();
        assertThat(deleted).as("the hole was stored before").isPositive();
    }

    private static GapDetected gap(UUID pair, MarketType market, String timeframe, Instant from, Instant to) {
        return new GapDetected(pair, "BTCUSDT", market, timeframe, from, to);
    }

    private CandleBackfillService service(int spotPage, int futuresPage, CandleBackfillProperties.Depth depth) {
        CandleBackfillProperties properties = new CandleBackfillProperties(
                false,
                "0 1 * * * *",
                ZoneOffset.UTC,
                50,
                depth,
                new CandleBackfillProperties.PageSize(spotPage, futuresPage),
                Duration.ofDays(7));
        return new CandleBackfillServiceImpl(client, pairs, candles, properties, transactions, clock);
    }

    private static CandleBackfillProperties.Depth depths() {
        return new CandleBackfillProperties.Depth(
                Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(5), Duration.ofDays(10));
    }

    /** Closed candles between {@code NOW - depth} and the forming candle, by the exchange's alignment. */
    private long expectedClosed(String interval, Duration depth) {
        long step = SyntheticKlines.intervalOf(interval).toMillis();
        long from = NOW.minus(depth).toEpochMilli();
        long first = (from + step - 1) / step * step;
        long forming = NOW.toEpochMilli() / step * step;
        return (forming - first) / step;
    }

    private long rows(UUID pair, String timeframe) {
        return rows(pair, timeframe, MarketType.SPOT);
    }

    private long rows(UUID pair, String timeframe, MarketType market) {
        return sql.sql("select count(*) from ohlcv where pair_id = ? and timeframe = ? and market_type = ?")
                .params(pair, timeframe, market.name())
                .query(Long.class)
                .single();
    }

    private Instant latestOpen(UUID pair, String timeframe) {
        return candles.latestOpenTime(pair, MarketType.SPOT, timeframe).orElseThrow();
    }

    private UUID pair(String symbol, String spotStatus, String futuresStatus) {
        OffsetDateTime at = NOW.atOffset(ZoneOffset.UTC);
        String base = symbol.replace("USDT", "");
        UUID baseId = coin(base, at);
        UUID quoteId = coin("USDT", at);
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, pair_status,
                                                 spot_exchange_status, futures_exchange_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'INACTIVE', ?, ?, ?, ?)""")
                .params(id, baseId, quoteId, symbol, spotStatus, futuresStatus, at, at)
                .update();
        return id;
    }

    private UUID coin(String symbol, OffsetDateTime at) {
        return sql.sql("select coin_id from coin where symbol = ?")
                .param(symbol)
                .query(UUID.class)
                .optional()
                .orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    sql.sql(
                                    "insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                            .params(id, symbol, symbol, at, at)
                            .update();
                    return id;
                });
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
