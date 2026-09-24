package com.cryptopilot.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.repository.MarketSnapshotRepository;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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

/**
 * NSF-03's periodic snapshots against the migrated hypertables: one row per streamed pair and minute, every
 * value exact, nothing stale, nothing twice — and the latest-value store they are read from.
 *
 * <p>Rule: NSF-03 (latest prices cached; PERIODIC snapshots every minute); BR-07; TECHNICAL_DESIGN 5.4, 7.1
 * step 7.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class MarketSnapshotServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:03:17Z");
    private static final Instant MINUTE = Instant.parse("2026-09-24T10:03:00Z");

    @Autowired
    private MarketSnapshotRepository repository;

    @Autowired
    private JdbcClient sql;

    private final MutableTestClock clock = new MutableTestClock(NOW);
    private final LatestMarketData latest = new LatestMarketData();

    private MarketTestData data;
    private MarketSnapshotService service;
    private UUID btc;
    private UUID eth;

    @BeforeEach
    void setUp() {
        data = new MarketTestData(sql, NOW);
        btc = data.pair("BTCUSDT", true, true, "TRADING", "TRADING", 0);
        eth = data.pair("ETHUSDT", true, true, "TRADING", "TRADING", 0);
        service = new MarketSnapshotService(latest, repository, properties(), clock);
    }

    @AfterEach
    void clear() {
        data.clear();
    }

    /** One row per pair and market at the whole minute, every decimal as the exchange sent it. */
    @Test
    void NSF03_aSnapshot_writesTheLatestValuesAtTheMinute() {
        latest.recordTicker(btc, ticker(NOW.minusSeconds(1)));
        latest.recordMarkPrice(btc, markPrice(NOW.minusSeconds(1)));

        SnapshotRun run = service.writePeriodic();

        assertThat(run).isEqualTo(new SnapshotRun(MINUTE, 1, 1, 0));
        var spot = sql.sql("select * from spot_market_data where pair_id = ?")
                .param(btc)
                .query()
                .singleRow();
        assertThat(((Timestamp) spot.get("snapshot_time")).toInstant()).isEqualTo(MINUTE);
        assertThat((BigDecimal) spot.get("last_price")).isEqualByComparingTo("84282.01");
        assertThat((BigDecimal) spot.get("best_bid_price")).isEqualByComparingTo("84282.00");
        assertThat((BigDecimal) spot.get("best_ask_price")).isEqualByComparingTo("84282.02");
        assertThat((BigDecimal) spot.get("high_price_24h")).isEqualByComparingTo("84794.01");
        assertThat((BigDecimal) spot.get("low_price_24h")).isEqualByComparingTo("82874.93");
        assertThat((BigDecimal) spot.get("price_change_percent_24h")).isEqualByComparingTo("-0.235");
        assertThat((BigDecimal) spot.get("base_volume_24h")).isEqualByComparingTo("21165.54049");
        assertThat((BigDecimal) spot.get("quote_volume_24h")).isEqualByComparingTo("1778929165.5786042");
        var futures = sql.sql("select * from futures_market_data where pair_id = ?")
                .param(btc)
                .query()
                .singleRow();
        assertThat((BigDecimal) futures.get("mark_price")).isEqualByComparingTo("84286.39852899");
        assertThat((BigDecimal) futures.get("index_price")).isEqualByComparingTo("84316.26956522");
        assertThat((BigDecimal) futures.get("funding_rate")).isEqualByComparingTo("0.00001583");
        assertThat(((Timestamp) futures.get("next_funding_time")).toInstant())
                .isEqualTo(Instant.parse("2026-09-24T16:00:00Z"));
        assertThat(futures.get("open_interest"))
                .as("NSF-04's, not the stream's")
                .isNull();
    }

    /** A repeated run in the same minute writes nothing twice. */
    @Test
    void NSF03_aSecondRunInTheSameMinute_writesNothing() {
        latest.recordTicker(btc, ticker(NOW));
        service.writePeriodic();
        clock.advance(Duration.ofSeconds(30));

        assertThat(service.writePeriodic().spotRows()).isZero();
        clock.advance(Duration.ofSeconds(30));
        assertThat(service.writePeriodic().spotRows())
                .as("the next minute is a new row")
                .isOne();
    }

    /** A value older than the allowed age is not written as if it were current. */
    @Test
    void NSF03_aStaleValue_isNotWritten() {
        latest.recordTicker(btc, ticker(NOW.minus(Duration.ofMinutes(2))));
        latest.recordTicker(eth, ticker(NOW.minus(Duration.ofMinutes(2)).minusMillis(1)));
        latest.recordMarkPrice(eth, markPrice(NOW.minus(Duration.ofMinutes(5))));

        assertThat(service.writePeriodic()).isEqualTo(new SnapshotRun(MINUTE, 1, 0, 2));
    }

    /** The store keeps the newest message per pair, even when an older one arrives late. */
    @Test
    void NSF03_theLatestValue_winsEvenAgainstALateOlderMessage() {
        latest.recordTicker(btc, ticker(NOW));
        latest.recordTicker(btc, ticker(NOW.minusSeconds(5)));
        latest.recordMarkPrice(btc, markPrice(NOW.minusSeconds(5)));
        latest.recordMarkPrice(btc, markPrice(NOW));

        assertThat(latest.ticker(btc))
                .hasValueSatisfying(t -> assertThat(t.eventTime()).isEqualTo(NOW));
        assertThat(latest.markPrice(btc))
                .hasValueSatisfying(m -> assertThat(m.eventTime()).isEqualTo(NOW));
        assertThat(latest.ticker(eth)).isEmpty();
        assertThat(latest.markPrice(eth)).isEmpty();
    }

    /** BR-07: a pair no longer streamed on a market is dropped from it, and only from it. */
    @Test
    void BR07_aPairNoLongerStreamed_isDropped() {
        latest.recordTicker(btc, ticker(NOW));
        latest.recordTicker(eth, ticker(NOW));
        latest.recordMarkPrice(eth, markPrice(NOW));

        latest.retainOnly(MarketType.SPOT, List.of(btc));

        assertThat(latest.tickers()).containsOnlyKeys(btc);
        assertThat(latest.markPrices()).containsOnlyKeys(eth);
        latest.retainOnly(MarketType.FUTURES, List.of());
        assertThat(latest.markPrices()).isEqualTo(Map.of());
    }

    private static TickerMessage ticker(Instant at) {
        return new TickerMessage(
                "BTCUSDT",
                new BigDecimal("84282.01"),
                new BigDecimal("84282.00"),
                new BigDecimal("84282.02"),
                new BigDecimal("84794.01"),
                new BigDecimal("82874.93"),
                new BigDecimal("-0.235"),
                new BigDecimal("21165.54049"),
                new BigDecimal("1778929165.5786042"),
                at);
    }

    private static MarkPriceMessage markPrice(Instant at) {
        return new MarkPriceMessage(
                "BTCUSDT",
                new BigDecimal("84286.39852899"),
                new BigDecimal("84316.26956522"),
                new BigDecimal("0.00001583"),
                Instant.parse("2026-09-24T16:00:00Z"),
                at);
    }

    private static BinanceStreamProperties properties() {
        return new BinanceStreamProperties(
                false,
                URI.create("ws://127.0.0.1:1"),
                URI.create("ws://127.0.0.1:1/market"),
                100,
                Duration.ofSeconds(2),
                new BinanceStreamProperties.Reconnect(Duration.ofSeconds(1), Duration.ofSeconds(60), 20),
                Duration.ofHours(23),
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                Duration.ofMinutes(2),
                4,
                100);
    }
}
