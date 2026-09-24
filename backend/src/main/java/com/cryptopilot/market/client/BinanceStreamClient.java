package com.cryptopilot.market.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only class that opens Binance market streams: it names the streams NSF-03 reads and builds the
 * connections that carry them.
 *
 * <h2>The streams of one pair</h2>
 *
 * <ul>
 *   <li>{@code <symbol>@kline_15m}, {@code _1h}, {@code _4h}, {@code _1d} on both markets — the timeframes BR-08
 *       stores;
 *   <li>Spot {@code <symbol>@ticker} — last price, best bid and ask and the 24-hour statistics;
 *   <li>futures {@code <symbol>@markPrice@1s} — mark price, index price and the predicted funding rate.
 * </ul>
 *
 * <p>Five streams per pair and market. {@code kline_1m}, which TECHNICAL_DESIGN 7.1 also lists for the
 * matching engine and the alerts, is not opened here: nothing reads it yet, and it is one more name in
 * {@link #streamsOf} when T-042 needs it (D-43).
 *
 * <p>Public market streams only: no listen key, no user data stream, no API key (BR-09).
 *
 * <p>Rule: NSF-03; BR-08, BR-09; TECHNICAL_DESIGN 7.1 steps 1 and 6, 7.1.1.
 */
public final class BinanceStreamClient implements AutoCloseable {

    private final BinanceStreamProperties properties;
    private final HttpClient http;
    private final BinanceStreamParser parser;
    private final ScheduledExecutorService timer;
    private final ReconnectBackoff backoff;
    private final Clock clock;

    /** A client with its own HTTP client and timer thread, both closed with it. */
    public BinanceStreamClient(BinanceStreamProperties properties, JsonMapper json, Clock clock) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        this.parser = new BinanceStreamParser(json);
        this.timer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("market-stream-timer").daemon().factory());
        this.backoff = new ReconnectBackoff(
                properties.reconnect().initial(),
                properties.reconnect().max(),
                properties.reconnect().jitterPercent(),
                () -> ThreadLocalRandom.current().nextDouble());
        this.clock = clock;
    }

    /** The streams NSF-03 reads for one pair on one venue, as the exchange names them. */
    public static List<String> streamsOf(BinanceVenue venue, String symbol) {
        String s = symbol.toLowerCase(Locale.ROOT);
        List<String> streams = new ArrayList<>();
        for (MarketInterval timeframe : List.of(
                MarketInterval.FIFTEEN_MINUTES,
                MarketInterval.ONE_HOUR,
                MarketInterval.FOUR_HOURS,
                MarketInterval.ONE_DAY)) {
            streams.add(s + "@kline_" + timeframe.code());
        }
        streams.add(venue == BinanceVenue.SPOT ? s + "@ticker" : s + "@markPrice@1s");
        return List.copyOf(streams);
    }

    /** The most streams one connection carries. */
    public int maxStreamsPerConnection() {
        return properties.maxStreamsPerConnection();
    }

    /** A connection for these streams, not yet started. */
    public BinanceStreamShard shard(
            BinanceVenue venue, String name, List<String> streams, BinanceStreamShard.Listener listener) {
        URI base = venue == BinanceVenue.SPOT ? properties.spotUrl() : properties.futuresUrl();
        return new BinanceStreamShard(name, base, streams, http, parser, timer, properties, backoff, clock, listener);
    }

    /** Stops the timer and the HTTP client. Shards still open are aborted with it. */
    @Override
    public void close() {
        timer.shutdownNow();
        http.shutdownNow();
    }
}
