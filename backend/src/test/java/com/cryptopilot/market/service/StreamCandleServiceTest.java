package com.cryptopilot.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.client.MarketInterval;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.config.CandleBackfillProperties;
import com.cryptopilot.market.event.CandleClosed;
import com.cryptopilot.market.event.GapDetected;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * NSF-03's storage side against the migrated schema: which pairs are streamed, that only closed candles of the
 * stored timeframes are written, and that every gap before a closed candle is detected and named exactly.
 *
 * <p>Rule: NSF-03, NSF-02; BR-07, BR-08; TECHNICAL_DESIGN 7.1 steps 3 and 4; A-33.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class StreamCandleServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00.500Z");

    @Autowired
    private CryptoPairRepository pairs;

    @Autowired
    private OhlcvRepository candles;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private BinanceRestClient exchange;

    @Autowired
    private JdbcClient sql;

    private final MutableTestClock clock = new MutableTestClock(NOW);
    private final List<Object> events = new CopyOnWriteArrayList<>();

    private MarketTestData data;
    private StreamCandleService service;
    private UUID btc;
    private StreamTarget target;

    @BeforeEach
    void setUp() {
        data = new MarketTestData(sql, NOW);
        CandleBackfillService backfill = new CandleBackfillService(
                exchange,
                pairs,
                candles,
                new CandleBackfillProperties(
                        false,
                        "0 1 * * * *",
                        ZoneOffset.UTC,
                        50,
                        new CandleBackfillProperties.Depth(
                                Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(5), Duration.ofDays(10)),
                        new CandleBackfillProperties.PageSize(1000, 500),
                        Duration.ofDays(7)),
                transactions,
                clock);
        service = new StreamCandleService(pairs, candles, backfill, transactions, events::add, clock);
        btc = data.pair("BTCUSDT", true, true, "TRADING", "TRADING", 1);
        target = new StreamTarget(btc, "BTCUSDT");
    }

    @AfterEach
    void clear() {
        data.clear();
    }

    /** BR-07: enabled on the market by an administrator, and trading there; in display order. */
    @Test
    void BR07_theStreamedPairs_areTheEnabledOnesTradingOnTheMarket() {
        UUID eth = data.pair("ETHUSDT", true, false, "TRADING", "TRADING", 0);
        data.pair("SOLUSDT", false, false, "TRADING", "TRADING", 0);
        data.pair("XRPUSDT", true, true, "NOT_TRADING", "DELISTED", 0);
        data.pair("ADAUSDT", false, true, null, "TRADING", 5);

        assertThat(service.targets(MarketType.SPOT)).containsExactly(new StreamTarget(eth, "ETHUSDT"), target);
        assertThat(service.targets(MarketType.FUTURES))
                .extracting(StreamTarget::symbol)
                .containsExactly("BTCUSDT", "ADAUSDT");
    }

    /** BR-08: a closed candle is stored and announced; nothing is missing before the first one after a stored one. */
    @Test
    void NSF03_aClosedCandle_isStoredAndAnnounced() {
        store(MarketType.SPOT, "1h", Instant.parse("2026-09-24T08:00:00Z"));

        assertThat(service.onKline(MarketType.SPOT, target, closed(MarketInterval.ONE_HOUR, "2026-09-24T09:00:00Z")))
                .isTrue();

        assertThat(rows("1h")).isEqualTo(2);
        assertThat(events).singleElement().isInstanceOfSatisfying(CandleClosed.class, closed -> {
            assertThat(closed.pairId()).isEqualTo(btc);
            assertThat(closed.symbol()).isEqualTo("BTCUSDT");
            assertThat(closed.market()).isEqualTo(MarketType.SPOT);
            assertThat(closed.timeframe()).isEqualTo("1h");
            assertThat(closed.openTime()).isEqualTo(Instant.parse("2026-09-24T09:00:00Z"));
            assertThat(closed.closeTime()).isEqualTo(Instant.parse("2026-09-24T09:59:59.999Z"));
            assertThat(closed.open()).isEqualTo(new BigDecimal("100.10"));
            assertThat(closed.high()).isEqualTo(new BigDecimal("102.30"));
            assertThat(closed.low()).isEqualTo(new BigDecimal("99.40"));
            assertThat(closed.close()).isEqualTo(new BigDecimal("101.20"));
            assertThat(closed.baseVolume()).isEqualTo(new BigDecimal("12.5"));
            assertThat(closed.quoteVolume()).isEqualTo(new BigDecimal("1260.75"));
        });
    }

    /** BR-08: a forming candle, or a 1m one, is never stored. */
    @Test
    void BR08_aFormingCandleOrAOneMinuteCandle_isNotStored() {
        KlineMessage forming = new KlineMessage(
                "BTCUSDT", MarketInterval.ONE_HOUR, kline(MarketInterval.ONE_HOUR, "2026-09-24T10:00:00Z"), false, NOW);

        assertThat(service.onKline(MarketType.SPOT, target, forming)).isFalse();
        assertThat(service.onKline(MarketType.SPOT, target, closed(MarketInterval.ONE_MINUTE, "2026-09-24T09:59:00Z")))
                .isFalse();

        assertThat(sql.sql("select count(*) from ohlcv").query(Long.class).single())
                .isZero();
        assertThat(events).isEmpty();
    }

    /** 7.1 step 4: a candle more than one timeframe after the latest stored names the candles between. */
    @Test
    void NSF03_aCandleAfterMissingOnes_reportsExactlyTheMissingRange() {
        store(MarketType.FUTURES, "15m", Instant.parse("2026-09-24T08:00:00Z"));

        service.onKline(MarketType.FUTURES, target, closed(MarketInterval.FIFTEEN_MINUTES, "2026-09-24T09:45:00Z"));

        assertThat(events)
                .first()
                .isEqualTo(new GapDetected(
                        btc,
                        "BTCUSDT",
                        MarketType.FUTURES,
                        "15m",
                        Instant.parse("2026-09-24T08:15:00Z"),
                        Instant.parse("2026-09-24T09:30:00Z")));
        assertThat(events.get(1)).isInstanceOf(CandleClosed.class);
    }

    /** One missing candle is a gap of one. */
    @Test
    void NSF03_oneMissingCandle_isAGapOfOne() {
        store(MarketType.FUTURES, "1d", Instant.parse("2026-09-21T00:00:00Z"));

        service.onKline(MarketType.FUTURES, target, closed(MarketInterval.ONE_DAY, "2026-09-23T00:00:00Z"));

        assertThat(events).first().isInstanceOfSatisfying(GapDetected.class, gap -> {
            assertThat(gap.from()).isEqualTo(Instant.parse("2026-09-22T00:00:00Z"));
            assertThat(gap.to()).isEqualTo(gap.from());
        });
    }

    /**
     * A series with nothing stored is missing its whole depth: the backfill resumes after the latest stored
     * candle and would otherwise never go back behind this one.
     */
    @Test
    void NSF03_theFirstCandleOfAnEmptySeries_reportsItsWholeDepthAsMissing() {
        service.onKline(MarketType.SPOT, target, closed(MarketInterval.FOUR_HOURS, "2026-09-24T04:00:00Z"));

        assertThat(events)
                .first()
                .isEqualTo(new GapDetected(
                        btc,
                        "BTCUSDT",
                        MarketType.SPOT,
                        "4h",
                        NOW.minus(Duration.ofDays(5)),
                        Instant.parse("2026-09-24T00:00:00Z")));
    }

    /** Candles that follow each other, a repeat, and a late older one: no gap, and the latest is kept. */
    @Test
    void NSF03_consecutiveRepeatedAndLateCandles_reportNoGap() {
        store(MarketType.SPOT, "1h", Instant.parse("2026-09-24T05:00:00Z"));

        service.onKline(MarketType.SPOT, target, closed(MarketInterval.ONE_HOUR, "2026-09-24T06:00:00Z"));
        service.onKline(MarketType.SPOT, target, closed(MarketInterval.ONE_HOUR, "2026-09-24T07:00:00Z"));
        service.onKline(MarketType.SPOT, target, closed(MarketInterval.ONE_HOUR, "2026-09-24T07:00:00Z"));
        service.onKline(MarketType.SPOT, target, closed(MarketInterval.ONE_HOUR, "2026-09-24T03:00:00Z"));
        service.onKline(MarketType.SPOT, target, closed(MarketInterval.ONE_HOUR, "2026-09-24T08:00:00Z"));

        assertThat(events).hasSize(5).allSatisfy(event -> assertThat(event).isInstanceOf(CandleClosed.class));
        assertThat(rows("1h")).as("the repeat is stored once").isEqualTo(5);
    }

    /** Each market and timeframe is its own series. */
    @Test
    void NSF03_seriesAreKeptApart() {
        store(MarketType.SPOT, "1h", Instant.parse("2026-09-24T08:00:00Z"));

        service.onKline(MarketType.SPOT, target, closed(MarketInterval.ONE_HOUR, "2026-09-24T09:00:00Z"));
        service.onKline(MarketType.FUTURES, target, closed(MarketInterval.ONE_HOUR, "2026-09-24T09:00:00Z"));

        assertThat(events)
                .filteredOn(GapDetected.class::isInstance)
                .singleElement()
                .satisfies(gap -> assertThat(((GapDetected) gap).market()).isEqualTo(MarketType.FUTURES));
    }

    private void store(MarketType market, String timeframe, Instant open) {
        MarketInterval interval = MarketInterval.fromCode(timeframe).orElseThrow();
        candles.insertAll(btc, market, timeframe, List.of(kline(interval, open.toString())));
    }

    private long rows(String timeframe) {
        return sql.sql("select count(*) from ohlcv where pair_id = ? and timeframe = ?")
                .params(btc, timeframe)
                .query(Long.class)
                .single();
    }

    private static KlineMessage closed(MarketInterval interval, String open) {
        Kline kline = kline(interval, open);
        return new KlineMessage(
                "BTCUSDT", interval, kline, true, kline.closeTime().plusMillis(1));
    }

    private static Kline kline(MarketInterval interval, String open) {
        Instant start = Instant.parse(open);
        return new Kline(
                start,
                start.plus(interval.duration()).minusMillis(1),
                new BigDecimal("100.10"),
                new BigDecimal("102.30"),
                new BigDecimal("99.40"),
                new BigDecimal("101.20"),
                new BigDecimal("12.5"),
                new BigDecimal("1260.75"),
                42);
    }
}
