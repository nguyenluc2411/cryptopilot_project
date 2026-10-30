package com.cryptopilot.market.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.market.model.enums.BinanceVenue;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The streams NSF-03 opens per pair, and where the client points each venue's connections.
 *
 * <p>Rule: NSF-03; BR-08 (the stored timeframes only); TECHNICAL_DESIGN 7.1, 7.1.1.
 */
class BinanceStreamClientTest {

    @Test
    void NSF03_aSpotPair_streamsItsMinuteCandlesItsFourStoredTimeframesAndItsTicker() {
        assertThat(BinanceStreamClient.streamsOf(BinanceVenue.SPOT, "BTCUSDT"))
                .containsExactly(
                        "btcusdt@kline_1m",
                        "btcusdt@kline_15m",
                        "btcusdt@kline_1h",
                        "btcusdt@kline_4h",
                        "btcusdt@kline_1d",
                        "btcusdt@ticker");
    }

    @Test
    void NSF03_aFuturesPair_streamsItsMinuteCandlesItsFourStoredTimeframesItsTickerAndItsMarkPrice() {
        assertThat(BinanceStreamClient.streamsOf(BinanceVenue.USD_M_FUTURES, "ETHUSDT"))
                .containsExactly(
                        "ethusdt@kline_1m",
                        "ethusdt@kline_15m",
                        "ethusdt@kline_1h",
                        "ethusdt@kline_4h",
                        "ethusdt@kline_1d",
                        "ethusdt@ticker",
                        "ethusdt@markPrice@1s");
    }

    /**
     * TR-03, TR-38: a Spot pair adds its trades, best bid and ask and depth; a futures pair adds its trades to its
     * /market streams and puts its best bid and ask and depth on the /public route.
     */
    @Test
    void TR03_withTradingStreams_aSpotPairAddsItsTradesBookTickerAndDepth() {
        try (BinanceStreamClient client = new BinanceStreamClient(
                properties(new BinanceStreamProperties.Trading(
                        true, true, URI.create("wss://fstream.binance.com/public"), 10, Duration.ofSeconds(10), 50)),
                JsonMapper.builder().build(),
                Clock.systemUTC())) {
            assertThat(client.streamsFor(BinanceVenue.SPOT, "BTCUSDT"))
                    .startsWith(BinanceStreamClient.streamsOf(BinanceVenue.SPOT, "BTCUSDT")
                            .toArray(String[]::new))
                    .endsWith("btcusdt@aggTrade", "btcusdt@bookTicker", "btcusdt@depth10@100ms")
                    .hasSize(9);
            assertThat(client.publicStreamsFor(BinanceVenue.SPOT, "BTCUSDT")).isEmpty();
            assertThat(client.streamsFor(BinanceVenue.USD_M_FUTURES, "BTCUSDT"))
                    .startsWith(BinanceStreamClient.streamsOf(BinanceVenue.USD_M_FUTURES, "BTCUSDT")
                            .toArray(String[]::new))
                    .endsWith("btcusdt@aggTrade")
                    .hasSize(8);
            assertThat(client.publicStreamsFor(BinanceVenue.USD_M_FUTURES, "BTCUSDT"))
                    .containsExactly("btcusdt@bookTicker", "btcusdt@depth10@100ms");
        }
        try (BinanceStreamClient client = new BinanceStreamClient(
                properties(new BinanceStreamProperties.Trading(
                        false, false, URI.create("wss://fstream.binance.com/public"), 20, Duration.ofSeconds(10), 50)),
                JsonMapper.builder().build(),
                Clock.systemUTC())) {
            assertThat(client.streamsFor(BinanceVenue.SPOT, "BTCUSDT"))
                    .isEqualTo(BinanceStreamClient.streamsOf(BinanceVenue.SPOT, "BTCUSDT"));
            assertThat(client.streamsFor(BinanceVenue.USD_M_FUTURES, "BTCUSDT"))
                    .isEqualTo(BinanceStreamClient.streamsOf(BinanceVenue.USD_M_FUTURES, "BTCUSDT"));
            assertThat(client.publicStreamsFor(BinanceVenue.USD_M_FUTURES, "BTCUSDT"))
                    .isEmpty();
        }
    }

    @Test
    void TR03_theDepthCarriesOnlyTheLevelsBinanceOffers() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BinanceStreamProperties.Trading(
                        true, true, URI.create("wss://fstream.binance.com/public"), 15, Duration.ofSeconds(10), 50))
                .withMessageContaining("5, 10 or 20");
    }

    private static BinanceStreamProperties properties(BinanceStreamProperties.Trading trading) {
        return new BinanceStreamProperties(
                true,
                URI.create("wss://stream.binance.com:9443"),
                URI.create("wss://fstream.binance.com/market"),
                100,
                Duration.ofSeconds(10),
                new BinanceStreamProperties.Reconnect(Duration.ofSeconds(1), Duration.ofSeconds(60), 20),
                Duration.ofHours(23),
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                Duration.ofMinutes(2),
                4,
                10000,
                trading);
    }

    /** Each venue's connections go to its own host; futures keep the /market route (7.1.1). */
    @Test
    void NSF03_eachVenue_connectsToItsOwnHost() {
        BinanceStreamProperties properties = new BinanceStreamProperties(
                true,
                URI.create("wss://stream.binance.com:9443"),
                URI.create("wss://fstream.binance.com/market"),
                100,
                Duration.ofSeconds(10),
                new BinanceStreamProperties.Reconnect(Duration.ofSeconds(1), Duration.ofSeconds(60), 20),
                Duration.ofHours(23),
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                Duration.ofMinutes(2),
                4,
                10000,
                new BinanceStreamProperties.Trading(
                        false, false, URI.create("wss://fstream.binance.com/public"), 20, Duration.ofSeconds(10), 50));
        BinanceStreamShard.Listener ignore = new BinanceStreamShard.Listener() {
            @Override
            public void onMessage(StreamMessage message) {}

            @Override
            public void onConnected(BinanceStreamShard shard, boolean afterLoss) {}
        };

        try (BinanceStreamClient client =
                new BinanceStreamClient(properties, JsonMapper.builder().build(), Clock.systemUTC())) {
            assertThat(client.maxStreamsPerConnection()).isEqualTo(100);
            assertThat(client.shard(BinanceVenue.SPOT, "SPOT-0", java.util.List.of("btcusdt@ticker"), ignore)
                            .uri())
                    .hasToString("wss://stream.binance.com:9443/stream?streams=btcusdt@ticker");
            assertThat(client.shard(
                                    BinanceVenue.USD_M_FUTURES,
                                    "FUTURES-0",
                                    java.util.List.of("btcusdt@markPrice@1s"),
                                    ignore)
                            .uri())
                    .hasToString("wss://fstream.binance.com/market/stream?streams=btcusdt@markPrice@1s");
            assertThat(client.publicShard("FUTURES-PUBLIC-1", java.util.List.of("btcusdt@bookTicker"), ignore)
                            .uri())
                    .hasToString("wss://fstream.binance.com/public/stream?streams=btcusdt@bookTicker");
        }
    }
}
