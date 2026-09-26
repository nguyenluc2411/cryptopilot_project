package com.cryptopilot.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.calculator.SetupComponents;
import com.cryptopilot.market.calculator.SwingSupportResistance;
import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.event.CandleClosed;
import com.cryptopilot.market.model.ComponentInputs;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.IndicatorSnapshot;
import com.cryptopilot.market.model.SeriesKey;
import com.cryptopilot.market.model.StoredIndicators;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.market.repository.TechnicalIndicatorRepository;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * NSF-05 over stored candles on TimescaleDB: each closed candle gets one {@code technical_indicator} row holding the
 * indicators of T-025, the levels of T-026 and the component scores of T-027, written again in place when the same
 * candle is processed again.
 *
 * <p>Rule: NSF-05; BR-12, BR-13, BR-14; TECHNICAL_DESIGN 5.4 and 7.4; D-53 (rule 3).
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class IndicatorPipelineServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Duration HOUR = Duration.ofHours(1);

    @Autowired
    private IndicatorPipelineService pipeline;

    @Autowired
    private IndicatorService indicators;

    @Autowired
    private OhlcvRepository candles;

    @Autowired
    private TechnicalIndicatorRepository store;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private JdbcClient sql;

    private MarketTestData data;
    private UUID pair;

    @BeforeEach
    void setUp() {
        data = new MarketTestData(sql, NOW);
        pair = data.pair("BTCUSDT", true, true, "TRADING", "TRADING", 1);
    }

    @AfterEach
    void clear() {
        data.clear();
    }

    @Test
    void NSF05_aClosedCandle_storesOneRowWithTheComponentsT027Computes() {
        List<Kline> series = series(260);
        candles.insertAll(pair, MarketType.SPOT, "1h", series);
        pipeline.onCandleClosed(closed(MarketType.SPOT, series.get(258)));

        StoredIndicators row = pipeline.onCandleClosed(closed(MarketType.SPOT, series.get(259)))
                .orElseThrow();

        IndicatorSnapshot snapshot =
                indicators.current(new SeriesKey(pair, MarketType.SPOT, "1h")).orElseThrow();
        SwingSupportResistance.Levels levels = SwingSupportResistance.nearest(candles.closedCandles(
                pair, MarketType.SPOT, "1h", null, series.get(259).openTime().plus(HOUR), 200));
        BigDecimal previous = store.macdHistogram(
                        pair, MarketType.SPOT, "1h", series.get(258).openTime())
                .orElseThrow();
        ComponentScores expected = SetupComponents.compute(new ComponentInputs(
                MarketType.SPOT,
                "1h",
                series.get(259).close(),
                series.get(259).volume(),
                snapshot,
                previous.doubleValue(),
                levels.support(),
                levels.resistance(),
                null));
        assertThat(rows()).isEqualTo(2);
        assertThat(row.components()).isEqualTo(expected);
        assertThat(store.latest(pair, MarketType.SPOT, "1h").orElseThrow().components())
                .isEqualTo(expected);
        assertThat(row.ema200()).isEqualByComparingTo(tenPlaces(snapshot.ema200()));
        assertThat(row.macdHistogram()).isEqualByComparingTo(tenPlaces(snapshot.macdHistogram()));
        assertThat(row.nearestSupport()).isEqualTo(tenPlaces(levels.support()));
        assertThat(row.nearestResistance()).isEqualTo(tenPlaces(levels.resistance()));
        assertThat(row.components().trend()).isNotNull();
        assertThat(row.components().momentum()).isNotNull();
        assertThat(row.components().formulaVersion()).isEqualTo("v1");
    }

    @Test
    void NSF05_theSameCandleAgain_overwritesItsRowAndAddsNone() {
        List<Kline> series = series(40);
        candles.insertAll(pair, MarketType.SPOT, "1h", series);
        CandleClosed last = closed(MarketType.SPOT, series.get(39));
        StoredIndicators first = pipeline.onCandleClosed(last).orElseThrow();
        sql.sql("update technical_indicator set level_score = 1, ema_20 = 1 where pair_id = ?")
                .param(pair)
                .update();

        pipeline.onCandleClosed(last);

        assertThat(rows()).isEqualTo(1);
        StoredIndicators again = store.latest(pair, MarketType.SPOT, "1h").orElseThrow();
        assertThat(again.components()).isEqualTo(first.components());
        assertThat(again.ema20()).isEqualByComparingTo(first.ema20());
    }

    /** 40 candles: no EMA200 and no earlier row, so trend and momentum cannot be computed and are stored null. */
    @Test
    void NSF05_componentsWithoutTheirInputs_areStoredNull() {
        List<Kline> series = series(40);
        candles.insertAll(pair, MarketType.SPOT, "1h", series);

        pipeline.onCandleClosed(closed(MarketType.SPOT, series.get(39)));

        StoredIndicators row = store.latest(pair, MarketType.SPOT, "1h").orElseThrow();
        assertThat(row.ema200()).isNull();
        assertThat(row.components().trend()).isNull();
        assertThat(row.components().momentum()).isNull();
        assertThat(row.components().volume()).isNotNull();
        assertThat(row.components().level()).isNotNull();
        assertThat(row.components().derivatives()).isNull();
    }

    @Test
    void NSF05_futures_storesTheDerivativesComponentFromTheFuturesData() {
        List<Kline> series = series(40);
        candles.insertAll(pair, MarketType.FUTURES, "1h", series);
        Instant close = series.get(39).closeTime();
        futuresData(close.minus(HOUR).minusSeconds(30), "100", null, "5000");
        futuresData(close.minusSeconds(30), "101", "0.00025", "5100");

        pipeline.onCandleClosed(closed(MarketType.FUTURES, series.get(39)));

        // funding 50·(0.0005 + 0.00025)/0.001 = 37.5; price and open interest both rose: 50
        assertThat(store.latest(pair, MarketType.FUTURES, "1h")
                        .orElseThrow()
                        .components()
                        .derivatives())
                .isEqualByComparingTo("87.50");
    }

    /** Only a reading at the close and none an hour before: the changes are unknown, so is the component. */
    @Test
    void NSF05_futuresWithoutAnHourOfData_storesDerivativesNull() {
        List<Kline> series = series(40);
        candles.insertAll(pair, MarketType.FUTURES, "1h", series);
        futuresData(series.get(39).closeTime().minusSeconds(30), "101", "0.0001", "5100");

        pipeline.onCandleClosed(closed(MarketType.FUTURES, series.get(39)));

        assertThat(store.latest(pair, MarketType.FUTURES, "1h")
                        .orElseThrow()
                        .components()
                        .derivatives())
                .isNull();
    }

    @Test
    void NSF05_futuresWithoutFuturesData_storesDerivativesNull_andSpotNeverHasOne() {
        List<Kline> series = series(40);
        candles.insertAll(pair, MarketType.FUTURES, "1h", series);
        candles.insertAll(pair, MarketType.SPOT, "1h", series);

        pipeline.onCandleClosed(closed(MarketType.FUTURES, series.get(39)));
        pipeline.onCandleClosed(closed(MarketType.SPOT, series.get(39)));

        assertThat(store.latest(pair, MarketType.FUTURES, "1h")
                        .orElseThrow()
                        .components()
                        .derivatives())
                .isNull();
        assertThat(store.latest(pair, MarketType.SPOT, "1h")
                        .orElseThrow()
                        .components()
                        .derivatives())
                .isNull();
        assertThat(sql.sql(
                                "select count(*) from technical_indicator where pair_id = ? and derivatives_score is not null")
                        .param(pair)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void NSF05_latest_isTheRowOfTheNewestCandleOfThatSeries() {
        List<Kline> series = series(45);
        candles.insertAll(pair, MarketType.SPOT, "1h", series);
        candles.insertAll(pair, MarketType.SPOT, "4h", series.subList(0, 30));
        for (int i = 40; i < 45; i++) {
            pipeline.onCandleClosed(closed(MarketType.SPOT, series.get(i)));
        }

        assertThat(store.latest(pair, MarketType.SPOT, "1h").orElseThrow().openTime())
                .isEqualTo(series.get(44).openTime());
        assertThat(store.latest(pair, MarketType.SPOT, "4h")).isEmpty();
        assertThat(store.latest(pair, MarketType.FUTURES, "1h")).isEmpty();
        assertThat(rows()).isEqualTo(5);
    }

    /** A candle before the one the series already holds is refused by the indicators: no row is written for it. */
    @Test
    void NSF05_anOutOfOrderCandle_writesNoRow() {
        List<Kline> series = series(40);
        candles.insertAll(pair, MarketType.SPOT, "1h", series);
        pipeline.onCandleClosed(closed(MarketType.SPOT, series.get(39)));

        assertThat(pipeline.onCandleClosed(closed(MarketType.SPOT, series.get(35))))
                .isEmpty();

        assertThat(rows()).isEqualTo(1);
    }

    /** A candle that is not stored leaves the indicators with nothing to rebuild from: no row. */
    @Test
    void NSF05_aCandleNotStored_writesNoRow() {
        assertThat(pipeline.onCandleClosed(closed(MarketType.SPOT, series(1).getFirst())))
                .isEmpty();

        assertThat(rows()).isZero();
    }

    /** The listener is wired: publishing the event NSF-03 publishes is enough to get the row. */
    @Test
    void NSF05_thePublishedEvent_reachesThePipeline() {
        List<Kline> series = series(40);
        candles.insertAll(pair, MarketType.SPOT, "1h", series);

        events.publishEvent(closed(MarketType.SPOT, series.get(39)));

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void D53_technicalIndicator_hasNoSetupScoreBiasOrDominantSideColumn() {
        List<String> columns = sql.sql("""
                        select column_name from information_schema.columns
                         where table_name = 'technical_indicator'""").query(String.class).list();

        assertThat(columns)
                .contains("trend_score", "momentum_score", "volume_score", "level_score", "derivatives_score")
                .doesNotContain("setup_score", "setup_bias", "dominant_side");
    }

    private int rows() {
        return sql.sql("select count(*) from technical_indicator where pair_id = ?")
                .param(pair)
                .query(Integer.class)
                .single();
    }

    private void futuresData(Instant at, String markPrice, String fundingRate, String openInterest) {
        sql.sql("""
                        insert into futures_market_data (pair_id, snapshot_time, mark_price, funding_rate, open_interest)
                        values (?, ?, ?, ?, ?)""")
                .params(
                        pair,
                        Timestamp.from(at),
                        new BigDecimal(markPrice),
                        fundingRate == null ? null : new BigDecimal(fundingRate),
                        new BigDecimal(openInterest))
                .update();
    }

    private CandleClosed closed(MarketType market, Kline k) {
        return new CandleClosed(
                pair,
                "BTCUSDT",
                market,
                "1h",
                k.openTime(),
                k.closeTime(),
                k.open(),
                k.high(),
                k.low(),
                k.close(),
                k.volume(),
                k.quoteVolume());
    }

    /** A rising series with a 12-candle wave, so there are swing lows and highs and a varying volume. */
    private static List<Kline> series(int size) {
        List<Kline> klines = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            BigDecimal close = BigDecimal.valueOf(100 + i * 0.1 + 5 * Math.sin(i * Math.PI / 6))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal volume = BigDecimal.valueOf(10 + (i % 7));
            Instant open = START.plus(HOUR.multipliedBy(i));
            klines.add(new Kline(
                    open,
                    open.plus(HOUR).minusMillis(1),
                    close,
                    close.add(BigDecimal.ONE),
                    close.subtract(BigDecimal.ONE),
                    close,
                    volume,
                    volume.multiply(close),
                    1));
        }
        return klines;
    }

    private static BigDecimal tenPlaces(Double value) {
        return tenPlaces(BigDecimal.valueOf(value));
    }

    private static BigDecimal tenPlaces(BigDecimal value) {
        return value == null ? null : value.setScale(10, RoundingMode.HALF_UP);
    }
}
