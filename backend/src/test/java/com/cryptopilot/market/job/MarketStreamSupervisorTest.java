package com.cryptopilot.market.job;

import static com.cryptopilot.market.client.StubStreamServer.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.BinanceStreamClient;
import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.client.MarketInterval;
import com.cryptopilot.market.client.StreamFrames;
import com.cryptopilot.market.client.StubStreamServer;
import com.cryptopilot.market.client.StubStreamServer.Connection;
import com.cryptopilot.market.config.CandleBackfillProperties;
import com.cryptopilot.market.event.MarketStreamReconnected;
import com.cryptopilot.market.event.SymbolsSynchronised;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.OhlcvRepository;
import com.cryptopilot.market.service.CandleBackfillService;
import com.cryptopilot.market.service.LatestMarketData;
import com.cryptopilot.market.service.StreamCandleService;
import com.cryptopilot.market.service.StreamTarget;
import com.cryptopilot.support.TestcontainersConfig;
import java.lang.reflect.Proxy;
import java.time.Clock;
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
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * NSF-03 end to end, from a local stream host to the database: which connections are opened for which pairs,
 * where each message goes, what a lost connection triggers, and how a change of the enabled pairs changes the
 * connections.
 *
 * <p>Rule: NSF-03; BR-07, BR-08; TECHNICAL_DESIGN 7.1 steps 1, 2, 3 and 6.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class MarketStreamSupervisorTest {

    private static final Instant AT = Instant.parse("2026-09-24T09:00:00Z");

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

    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final List<Duration> refreshes = new CopyOnWriteArrayList<>();
    private final LatestMarketData latest = new LatestMarketData();

    private MarketTestData data;
    private StubStreamServer server;
    private BinanceStreamClient streams;
    private MarketStreamSupervisor supervisor;
    private UUID btc;

    @BeforeEach
    void setUp() throws Exception {
        data = new MarketTestData(sql, AT);
        server = new StubStreamServer();
        btc = data.pair("BTCUSDT", true, true, "TRADING", "TRADING", 0);
    }

    @AfterEach
    void stopEverything() throws Exception {
        if (supervisor != null) {
            supervisor.stop();
        }
        if (streams != null) {
            streams.close();
        }
        server.close();
        data.clear();
    }

    /** Disabled, as in a test context or without the dev and prod profiles: nothing is opened or scheduled. */
    @Test
    void NSF03_aDisabledSupervisor_opensNothing() {
        supervisor = supervisor(false, 100);

        supervisor.start();
        supervisor.onSymbolsSynchronised(new SymbolsSynchronised(MarketType.SPOT));

        assertThat(supervisor.shards(MarketType.SPOT)).isEmpty();
        assertThat(refreshes).isEmpty();
        assertThat(server.paths()).isEmpty();
    }

    /** One connection per market, the pair's five streams on each, futures under its route; refresh scheduled. */
    @Test
    void NSF03_start_opensEachMarketsStreams_andSchedulesTheRefresh() {
        supervisor = supervisor(true, 100);

        supervisor.start();
        server.awaitConnection(2);

        assertThat(server.paths())
                .containsExactlyInAnyOrder(
                        "/stream?streams=btcusdt@kline_15m/btcusdt@kline_1h/btcusdt@kline_4h/btcusdt@kline_1d"
                                + "/btcusdt@ticker",
                        "/market/stream?streams=btcusdt@kline_15m/btcusdt@kline_1h/btcusdt@kline_4h"
                                + "/btcusdt@kline_1d/btcusdt@markPrice@1s");
        assertThat(refreshes).containsExactly(Duration.ofMinutes(5));
    }

    /** 7.1 step 1: connections carry at most the configured number of streams, a pair never split. */
    @Test
    void NSF03_pairs_arePackedIntoConnectionsWithoutSplittingOne() {
        data.pair("ETHUSDT", true, false, "TRADING", null, 1);
        data.pair("SOLUSDT", true, false, "TRADING", null, 2);
        supervisor = supervisor(true, 12);

        supervisor.refresh(MarketType.SPOT);

        assertThat(supervisor.shards(MarketType.SPOT))
                .extracting(shard -> shard.streams().size())
                .containsExactly(10, 5);
        assertThat(supervisor.shards(MarketType.SPOT).get(1).streams()).allMatch(name -> name.startsWith("solusdt@"));
    }

    /** A market with no pair to stream opens no connection. */
    @Test
    void NSF03_aMarketWithNothingToStream_opensNoConnection() {
        data.enable(btc, true, false);
        supervisor = supervisor(true, 100);

        supervisor.refresh(MarketType.FUTURES);

        assertThat(supervisor.shards(MarketType.FUTURES)).isEmpty();
    }

    /**
     * Where each message goes: a closed candle into {@code ohlcv}, a forming one nowhere (BR-08), prices into the
     * latest values, a symbol not streamed ignored.
     */
    @Test
    void NSF03_messages_areRoutedToStorageAndTheLatestValues() {
        supervisor = supervisor(true, 100);
        supervisor.start();
        server.awaitConnection(2);
        Connection spot = connection("/stream");
        Connection futures = connection("/market/stream");

        spot.send(StreamFrames.ticker("BTCUSDT", AT));
        spot.send(StreamFrames.ticker("DOGEUSDT", AT));
        spot.send(StreamFrames.kline("BTCUSDT", MarketInterval.ONE_HOUR, AT, false));
        spot.send(StreamFrames.kline("BTCUSDT", MarketInterval.ONE_HOUR, AT, true));
        futures.send(StreamFrames.markPrice("BTCUSDT", AT));

        await(() -> latest.ticker(btc).isPresent() && latest.markPrice(btc).isPresent(), "the latest values");
        await(() -> storedCandles() == 1, "the closed candle");
        assertThat(latest.tickers()).hasSize(1);
        assertThat(sql.sql("select open_time from ohlcv where pair_id = ? and timeframe = '1h'")
                        .param(btc)
                        .query(Instant.class)
                        .single())
                .isEqualTo(AT);
    }

    /** A lost connection that comes back publishes the reconnection; the first opening does not. */
    @Test
    void NSF03_aConnectionBackAfterALoss_isAnnounced() {
        data.enable(btc, true, false);
        supervisor = supervisor(true, 100);
        supervisor.start();
        Connection first = server.awaitConnection(1);
        await(() -> supervisor.shards(MarketType.SPOT).getFirst().isConnected(), "the first connection");
        assertThat(events).isEmpty();

        first.drop();
        server.awaitConnection(2);

        await(() -> !events.isEmpty(), "the reconnection event");
        assertThat(events).containsExactly(new MarketStreamReconnected(MarketType.SPOT));
    }

    /** An unchanged set of pairs keeps its connections; a changed one replaces them and drops old prices. */
    @Test
    void BR07_aChangeOfTheEnabledPairs_replacesTheConnections() {
        data.enable(btc, true, false);
        supervisor = supervisor(true, 100);
        supervisor.start();
        Connection first = server.awaitConnection(1);
        first.send(StreamFrames.ticker("BTCUSDT", AT));
        await(() -> latest.ticker(btc).isPresent(), "a price");

        assertThat(supervisor.refresh(MarketType.SPOT)).isFalse();
        UUID eth = data.pair("ETHUSDT", true, false, "TRADING", null, 1);
        data.enable(btc, false, false);
        supervisor.onSymbolsSynchronised(new SymbolsSynchronised(MarketType.SPOT));

        await(() -> first.closeCode() != null, "the old connection to be closed");
        server.awaitConnection(2);
        assertThat(server.paths().getLast()).startsWith("/stream?streams=ethusdt@kline_15m");
        assertThat(latest.ticker(btc)).as("BR-07: no longer collected").isEmpty();
        assertThat(supervisor.shards(MarketType.SPOT).getFirst().streams()).allMatch(s -> s.startsWith("ethusdt"));
        assertThat(eth).isNotNull();
    }

    /** A market whose pairs cannot be read keeps its connections; the other market is refreshed regardless. */
    @Test
    void NSF03_aFailedRefresh_leavesTheStreamsAsTheyAre() {
        supervisor = supervisor(
                true,
                100,
                new StreamCandleService(pairs, candles, backfill(), transactions, events::add, Clock.systemUTC()) {
                    @Override
                    public List<StreamTarget> targets(MarketType market) {
                        if (market == MarketType.SPOT) {
                            throw new IllegalStateException("the database is down");
                        }
                        return super.targets(market);
                    }
                });

        supervisor.refreshAll();

        assertThat(supervisor.shards(MarketType.SPOT)).isEmpty();
        assertThat(supervisor.shards(MarketType.FUTURES)).hasSize(1);
    }

    /** Stopping closes every connection. */
    @Test
    void NSF03_stop_closesEveryConnection() {
        supervisor = supervisor(true, 100);
        supervisor.start();
        server.awaitConnection(2);
        await(
                () -> supervisor.shards(MarketType.SPOT).getFirst().isConnected()
                        && supervisor.shards(MarketType.FUTURES).getFirst().isConnected(),
                "both connections");

        supervisor.stop();

        await(() -> server.connections().stream().allMatch(c -> c.closeCode() != null), "both closes");
        assertThat(supervisor.shards(MarketType.SPOT)).isEmpty();
    }

    private Connection connection(String pathStart) {
        await(() -> server.paths().stream().anyMatch(p -> p.startsWith(pathStart + "?")), pathStart);
        int index = 0;
        for (String path : server.paths()) {
            if (path.startsWith(pathStart + "?")) {
                return server.connections().get(index);
            }
            index++;
        }
        throw new AssertionError(pathStart);
    }

    private long storedCandles() {
        return sql.sql("select count(*) from ohlcv").query(Long.class).single();
    }

    private MarketStreamSupervisor supervisor(boolean enabled, int maxStreams) {
        return supervisor(
                enabled,
                maxStreams,
                new StreamCandleService(pairs, candles, backfill(), transactions, events::add, Clock.systemUTC()));
    }

    private MarketStreamSupervisor supervisor(boolean enabled, int maxStreams, StreamCandleService service) {
        BinanceStreamProperties properties = new BinanceStreamProperties(
                enabled,
                server.baseUrl(""),
                server.baseUrl("/market"),
                maxStreams,
                Duration.ofSeconds(2),
                new BinanceStreamProperties.Reconnect(Duration.ofMillis(20), Duration.ofMillis(100), 0),
                Duration.ofHours(23),
                Duration.ofHours(1),
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                Duration.ofMinutes(2),
                2,
                100);
        streams = new BinanceStreamClient(properties, JsonMapper.builder().build(), Clock.systemUTC());
        return new MarketStreamSupervisor(
                streams,
                service,
                latest,
                new ClosedCandlePipeline(service, properties),
                recordingScheduler(),
                properties,
                events::add);
    }

    private CandleBackfillService backfill() {
        return new CandleBackfillService(
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
                        new CandleBackfillProperties.PageSize(1000, 500)),
                transactions,
                Clock.systemUTC());
    }

    private TaskScheduler recordingScheduler() {
        return (TaskScheduler) Proxy.newProxyInstance(
                TaskScheduler.class.getClassLoader(), new Class<?>[] {TaskScheduler.class}, (proxy, method, args) -> {
                    if (method.getName().equals("scheduleWithFixedDelay") && args[1] instanceof Duration delay) {
                        refreshes.add(delay);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
