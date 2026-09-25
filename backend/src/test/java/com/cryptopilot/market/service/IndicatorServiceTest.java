package com.cryptopilot.market.service;

import static com.cryptopilot.market.calculator.IndicatorAssertions.assertMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.calculator.IndicatorEngine;
import com.cryptopilot.market.calculator.IndicatorFixture;
import com.cryptopilot.market.calculator.IndicatorFixture.Row;
import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.config.IndicatorProperties;
import com.cryptopilot.market.event.CandleClosed;
import com.cryptopilot.market.model.IndicatorOutcome;
import com.cryptopilot.market.model.IndicatorSnapshot;
import com.cryptopilot.market.model.IndicatorUpdate;
import com.cryptopilot.market.model.SeriesKey;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.market.service.impl.IndicatorServiceImpl;
import com.cryptopilot.support.TestcontainersConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The indicators of a series kept in memory and restored from the candles stored in {@code ohlcv}: the first candle
 * after start-up restores a series, the next one updates it, a repeated one changes nothing, and one out of order or
 * after a gap is refused and the series rebuilt from the database. The candles are the golden fixture's real ones,
 * stored through the repository NSF-03 writes with.
 *
 * <p>Rule: BR-12; NSF-05; SRS 3.3.2; TECHNICAL_DESIGN 7.2 and 10.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class IndicatorServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
    private static final Duration HOUR = Duration.ofHours(1);
    private static final double TOLERANCE = 1e-8;

    @Autowired
    private OhlcvRepository candles;

    @Autowired
    private IndicatorService service;

    @Autowired
    private JdbcClient sql;

    private final List<Row> fixture = IndicatorFixture.rows();

    private MarketTestData data;
    private UUID btc;
    private SeriesKey series;

    @BeforeEach
    void setUp() {
        data = new MarketTestData(sql, NOW);
        btc = data.pair("BTCUSDT", true, true, "TRADING", "TRADING", 1);
        series = new SeriesKey(btc, MarketType.SPOT, "1h");
    }

    @AfterEach
    void clear() {
        data.clear();
    }

    /** The service in the application context is the one configured from the properties (TECHNICAL_DESIGN 7.2). */
    @Test
    void BR12_theApplication_restoresFromTheLatestThousandCandles() {
        store(fixture.subList(0, 1100));

        IndicatorUpdate update = service.onCandleClosed(closed(fixture.get(1099)));

        assertThat(update.outcome()).isEqualTo(IndicatorOutcome.RESTORED);
        assertThat(update.snapshot().candles()).isEqualTo(1000);
        assertThat(update.snapshot().openTime()).isEqualTo(fixture.get(1099).openTime());
    }

    /** The first candle of a series after start-up builds it from the stored candles, including that one. */
    @Test
    void BR12_theFirstCandleAfterStartUp_restoresTheSeriesFromTheDatabase() {
        IndicatorService indicators = newService(1000);
        store(fixture.subList(0, 300));
        assertThat(indicators.current(series)).isEmpty();

        IndicatorUpdate update = indicators.onCandleClosed(closed(fixture.get(299)));

        assertThat(update.outcome()).isEqualTo(IndicatorOutcome.RESTORED);
        assertThat(update.snapshot()).isEqualTo(feed(fixture.subList(0, 300)).snapshot());
        assertMatches(update.snapshot(), fixture.get(299).expected(), TOLERANCE, TOLERANCE);
        assertThat(indicators.current(series)).contains(update.snapshot());
    }

    /** The next candle updates every indicator once; the same candle again changes nothing. */
    @Test
    void BR12_theNextCandleUpdates_andTheSameCandleAgainIsIgnored() {
        IndicatorService indicators = newService(1000);
        store(fixture.subList(0, 301));
        indicators.onCandleClosed(closed(fixture.get(299)));

        IndicatorUpdate next = indicators.onCandleClosed(closed(fixture.get(300)));
        IndicatorUpdate again = indicators.onCandleClosed(closed(fixture.get(300)));

        assertThat(next.outcome()).isEqualTo(IndicatorOutcome.ACCEPTED);
        assertMatches(next.snapshot(), fixture.get(300).expected(), TOLERANCE, TOLERANCE);
        assertThat(again.outcome()).isEqualTo(IndicatorOutcome.DUPLICATE);
        assertThat(again.snapshot()).isSameAs(next.snapshot());
    }

    /**
     * A candle after missing ones is refused and the series rebuilt from the database, which has them: the result is
     * the series that never missed any.
     */
    @Test
    void BR12_aCandleAfterAGap_isRefusedAndTheSeriesRebuiltFromTheDatabase() {
        IndicatorService indicators = newService(1000);
        store(fixture.subList(0, 304));
        indicators.onCandleClosed(closed(fixture.get(299)));

        IndicatorUpdate update = indicators.onCandleClosed(closed(fixture.get(303)));

        assertThat(update.outcome()).isEqualTo(IndicatorOutcome.GAP);
        assertThat(update.snapshot()).isEqualTo(feed(fixture.subList(0, 304)).snapshot());
        assertThat(indicators.onCandleClosed(closed(fixture.get(303))).outcome())
                .isEqualTo(IndicatorOutcome.DUPLICATE);
    }

    /** An older candle is refused and the series rebuilt up to the latest one it had taken — never set back. */
    @Test
    void BR12_anEarlierCandle_isRefusedAndTheSeriesRebuiltUpToTheLatest() {
        IndicatorService indicators = newService(1000);
        store(fixture.subList(0, 300));
        IndicatorSnapshot latest =
                indicators.onCandleClosed(closed(fixture.get(299))).snapshot();

        IndicatorUpdate update = indicators.onCandleClosed(closed(fixture.get(250)));

        assertThat(update.outcome()).isEqualTo(IndicatorOutcome.OUT_OF_ORDER);
        assertThat(update.snapshot()).isEqualTo(latest);
    }

    /**
     * Restart then continue: a service started again restores from the database and then goes on exactly as the one
     * that never stopped — EMA200 within 0.05% (TECHNICAL_DESIGN 7.2), every other indicator within the tolerance.
     */
    @Test
    void BR12_aRestartedService_continuesAsTheOneThatNeverStopped() {
        store(fixture);
        IndicatorService continuous = newService(1200);
        continuous.onCandleClosed(closed(fixture.get(899)));
        for (Row row : fixture.subList(900, 1000)) {
            continuous.onCandleClosed(closed(row));
        }

        IndicatorService restarted = newService(1000);
        for (Row row : fixture.subList(1000, 1200)) {
            IndicatorSnapshot expected = continuous.onCandleClosed(closed(row)).snapshot();
            IndicatorUpdate update = restarted.onCandleClosed(closed(row));
            IndicatorSnapshot actual = update.snapshot();
            assertThat(Math.abs(actual.ema200() - expected.ema200()) / expected.ema200())
                    .isLessThanOrEqualTo(5e-4);
            assertMatches(withEma200(actual, expected.ema200()), expected, TOLERANCE, TOLERANCE);
            assertMatches(expected, row.expected(), TOLERANCE, TOLERANCE);
        }
        assertThat(restarted.current(series).orElseThrow().candles()).isEqualTo(1000 + 199);
    }

    /**
     * Stored candles with a hole give no value across it: the series starts over after the hole and warms up again,
     * with no value where it has too few candles (SRS 3.3.2).
     */
    @Test
    void BR12_storedCandlesWithAHole_warmUpAgainAfterIt() {
        IndicatorService indicators = newService(1000);
        store(fixture.subList(0, 100));
        store(fixture.subList(101, 131));

        IndicatorSnapshot snapshot =
                indicators.onCandleClosed(closed(fixture.get(130))).snapshot();

        assertThat(snapshot.candles()).isEqualTo(30);
        assertThat(snapshot.sma20()).isNotNull();
        assertThat(snapshot.ema50()).isNull();
        assertThat(snapshot.ema200()).isNull();
        assertThat(snapshot).isEqualTo(feed(fixture.subList(101, 131)).snapshot());
    }

    /** A series with nothing stored has nothing to report until its candles are stored. */
    @Test
    void BR12_aSeriesWithNoStoredCandle_hasNoSnapshot() {
        IndicatorService indicators = newService(1000);

        IndicatorUpdate update = indicators.onCandleClosed(closed(fixture.get(0)));

        assertThat(update.outcome()).isEqualTo(IndicatorOutcome.RESTORED);
        assertThat(update.snapshot()).isNull();
        assertThat(indicators.current(series)).isEmpty();
        assertThat(indicators.onCandleClosed(closed(fixture.get(1))).outcome()).isEqualTo(IndicatorOutcome.ACCEPTED);
        assertThat(indicators.current(series).orElseThrow().candles()).isEqualTo(1);
    }

    /** Series never mix: another market or timeframe of the same pair has its own state. */
    @Test
    void BR12_eachSeries_hasItsOwnState() {
        IndicatorService indicators = newService(1000);
        store(fixture.subList(0, 50));
        indicators.onCandleClosed(closed(fixture.get(49)));

        assertThat(indicators.current(new SeriesKey(btc, MarketType.FUTURES, "1h")))
                .isEmpty();
        assertThat(indicators.current(new SeriesKey(btc, MarketType.SPOT, "4h")))
                .isEmpty();
        assertThat(indicators.current(series).orElseThrow().candles()).isEqualTo(50);
    }

    @Test
    void BR08_aTimeframeThatIsNotStored_isRejected() {
        IndicatorService indicators = newService(1000);
        Row row = fixture.get(0);
        CandleClosed candle = new CandleClosed(
                btc,
                "BTCUSDT",
                MarketType.SPOT,
                "7m",
                row.openTime(),
                row.openTime().plus(HOUR).minusMillis(1),
                row.close(),
                row.close(),
                row.close(),
                row.close(),
                row.volume(),
                row.volume());

        assertThatThrownBy(() -> indicators.onCandleClosed(candle)).isInstanceOf(IllegalArgumentException.class);
    }

    /** A service with its own state, as after a start-up; the history length as the property gives it. */
    private IndicatorService newService(int historyCandles) {
        return new IndicatorServiceImpl(candles, new IndicatorProperties(historyCandles));
    }

    private void store(List<Row> rows) {
        List<Kline> klines = rows.stream()
                .map(row -> new Kline(
                        row.openTime(),
                        row.openTime().plus(HOUR).minusMillis(1),
                        row.close(),
                        row.close(),
                        row.close(),
                        row.close(),
                        row.volume(),
                        row.volume().multiply(row.close()),
                        1))
                .toList();
        candles.insertAll(btc, MarketType.SPOT, "1h", klines);
    }

    private CandleClosed closed(Row row) {
        return new CandleClosed(
                btc,
                "BTCUSDT",
                MarketType.SPOT,
                "1h",
                row.openTime(),
                row.openTime().plus(HOUR).minusMillis(1),
                row.close(),
                row.close(),
                row.close(),
                row.close(),
                row.volume(),
                row.volume().multiply(row.close()));
    }

    private static IndicatorEngine feed(List<Row> rows) {
        IndicatorEngine engine = new IndicatorEngine(HOUR);
        for (Row row : rows) {
            engine.offer(row.openTime(), row.close().doubleValue(), row.volume().doubleValue());
        }
        return engine;
    }

    private static IndicatorSnapshot withEma200(IndicatorSnapshot s, Double ema200) {
        return new IndicatorSnapshot(
                s.openTime(),
                s.candles(),
                s.sma20(),
                s.ema20(),
                s.ema50(),
                ema200,
                s.rsi14(),
                s.macdLine(),
                s.macdSignal(),
                s.macdHistogram(),
                s.bbUpper(),
                s.bbMiddle(),
                s.bbLower(),
                s.volumeSma20());
    }
}
