package com.cryptopilot.market.client;

import static org.assertj.core.api.Assertions.assertThat;

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
    void NSF03_aSpotPair_streamsItsFourStoredTimeframesAndItsTicker() {
        assertThat(BinanceStreamClient.streamsOf(BinanceVenue.SPOT, "BTCUSDT"))
                .containsExactly(
                        "btcusdt@kline_15m",
                        "btcusdt@kline_1h",
                        "btcusdt@kline_4h",
                        "btcusdt@kline_1d",
                        "btcusdt@ticker");
    }

    @Test
    void NSF03_aFuturesPair_streamsItsFourStoredTimeframesAndItsMarkPrice() {
        assertThat(BinanceStreamClient.streamsOf(BinanceVenue.USD_M_FUTURES, "ETHUSDT"))
                .containsExactly(
                        "ethusdt@kline_15m",
                        "ethusdt@kline_1h",
                        "ethusdt@kline_4h",
                        "ethusdt@kline_1d",
                        "ethusdt@markPrice@1s");
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
                10000);
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
        }
    }
}
