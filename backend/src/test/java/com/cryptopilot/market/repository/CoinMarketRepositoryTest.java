package com.cryptopilot.market.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.CoinMarketRepository.OpenInterestPoint;
import com.cryptopilot.market.repository.CoinMarketRepository.Series;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The batched reads of the market list against PostgreSQL: one statement answers every series, Spot and Futures, with
 * the same closes a per-series read gave, and the open interest lands on whole hours.
 *
 * <p>Rule: SRS 3.3.1; BR-08.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class CoinMarketRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-17T06:20:00Z");

    @Autowired
    private CoinMarketRepository figures;

    @Autowired
    private JdbcClient sql;

    private UUID spot;
    private UUID futures;

    @BeforeEach
    void twoPairs() {
        MarketTestData data = new MarketTestData(sql, Instant.now());
        spot = data.pair("CMRAUSDT", true, false, "TRADING", null, 0);
        futures = data.pair("CMRBUSDT", false, true, null, "TRADING", 1);
    }

    @Test
    void theLatestCloseAtAnInstant_isReadForEverySeriesAtOnce() {
        // 15m candles of the Spot pair: the one closing at or before 05:20 is the 05:05 one.
        candle(spot, MarketType.SPOT, "15m", Instant.parse("2026-11-17T04:50:00Z"), Duration.ofMinutes(15), "100");
        candle(spot, MarketType.SPOT, "15m", Instant.parse("2026-11-17T05:05:00Z"), Duration.ofMinutes(15), "101");
        candle(spot, MarketType.SPOT, "15m", Instant.parse("2026-11-17T05:20:00Z"), Duration.ofMinutes(15), "102");
        candle(futures, MarketType.FUTURES, "15m", Instant.parse("2026-11-17T04:40:00Z"), Duration.ofMinutes(15), "7");
        // The same pair on the other market is another series.
        candle(spot, MarketType.FUTURES, "15m", Instant.parse("2026-11-17T05:05:00Z"), Duration.ofMinutes(15), "999");

        Map<Series, BigDecimal> closes = figures.closesAt(
                List.of(new Series(spot, MarketType.SPOT), new Series(futures, MarketType.FUTURES)),
                MarketInterval.FIFTEEN_MINUTES,
                NOW.minus(Duration.ofHours(1)),
                NOW.minus(Duration.ofMinutes(90)));

        assertThat(closes).hasSize(2);
        assertThat(closes.get(new Series(spot, MarketType.SPOT))).isEqualByComparingTo("101");
        assertThat(closes.get(new Series(futures, MarketType.FUTURES))).isEqualByComparingTo("7");
    }

    @Test
    void aSeriesWithoutACloseInTheWindow_isAbsent() {
        candle(spot, MarketType.SPOT, "1h", Instant.parse("2026-11-01T00:00:00Z"), Duration.ofHours(1), "50");

        Map<Series, BigDecimal> closes = figures.closesAt(
                List.of(new Series(spot, MarketType.SPOT)),
                MarketInterval.ONE_HOUR,
                NOW.minus(Duration.ofDays(7)),
                NOW.minus(Duration.ofDays(7).plusHours(3)));

        assertThat(closes).isEmpty();
        assertThat(figures.closesAt(List.of(), MarketInterval.ONE_HOUR, NOW, NOW.minusSeconds(1)))
                .isEmpty();
    }

    @Test
    void theClosesOfAPeriod_comeBackOldestFirstPerSeries() {
        Instant from = NOW.minus(Duration.ofHours(3));
        candle(spot, MarketType.SPOT, "1h", from.minus(Duration.ofHours(1)), Duration.ofHours(1), "1");
        candle(spot, MarketType.SPOT, "1h", from.plus(Duration.ofHours(1)), Duration.ofHours(1), "3");
        candle(spot, MarketType.SPOT, "1h", from, Duration.ofHours(1), "2");
        candle(futures, MarketType.FUTURES, "1h", from, Duration.ofHours(1), "9");

        Map<Series, List<BigDecimal>> closes = figures.closesBetween(
                List.of(new Series(spot, MarketType.SPOT), new Series(futures, MarketType.FUTURES)),
                MarketInterval.ONE_HOUR,
                from,
                NOW);

        assertThat(closes.get(new Series(spot, MarketType.SPOT)))
                .extracting(BigDecimal::toPlainString)
                .containsExactly("2.000000000000", "3.000000000000");
        assertThat(closes.get(new Series(futures, MarketType.FUTURES))).hasSize(1);
    }

    @Test
    void theOpenInterest_isSummedOnWholeHours() {
        Instant hour = Instant.parse("2026-11-17T05:00:00Z");
        openInterest(futures, hour.minusSeconds(120), "1000");
        openInterest(spot, hour.minusSeconds(60), "500");
        openInterest(futures, hour.plus(Duration.ofHours(1)).minusSeconds(30), "1100");

        List<OpenInterestPoint> points = figures.openInterestHourly(
                List.of(spot, futures), NOW.minus(Duration.ofHours(2)), NOW, Duration.ofMinutes(15));

        assertThat(points).extracting(OpenInterestPoint::at).containsExactly(hour, hour.plus(Duration.ofHours(1)));
        assertThat(points.getFirst().valueUsd()).isEqualByComparingTo("1500");
        assertThat(points.getFirst().pairs()).isEqualTo(2);
        assertThat(points.getLast().valueUsd()).isEqualByComparingTo("1100");
    }

    private void candle(UUID pair, MarketType market, String timeframe, Instant open, Duration length, String close) {
        sql.sql("""
                        insert into ohlcv (pair_id, market_type, timeframe, open_time, close_time, open_price,
                                           high_price, low_price, close_price, base_volume, quote_volume)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1)""")
                .params(
                        pair,
                        market.name(),
                        timeframe,
                        Timestamp.from(open),
                        Timestamp.from(open.plus(length).minusMillis(1)),
                        new BigDecimal(close),
                        new BigDecimal(close),
                        new BigDecimal(close),
                        new BigDecimal(close))
                .update();
    }

    private void openInterest(UUID pair, Instant at, String valueUsd) {
        sql.sql("""
                        insert into futures_market_data (pair_id, snapshot_time, open_interest, open_interest_value)
                        values (?, ?, 1, ?)""").params(pair, Timestamp.from(at), new BigDecimal(valueUsd)).update();
    }
}
