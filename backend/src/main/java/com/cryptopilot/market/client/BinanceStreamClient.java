package com.cryptopilot.market.client;

import com.cryptopilot.market.model.enums.BinanceVenue;
import com.cryptopilot.market.model.enums.MarketInterval;
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
 *   <li>{@code <symbol>@kline_1m} on both markets — the high and low the matching engine reads (D-09, Q-32);
 *       never stored;
 *   <li>{@code <symbol>@kline_15m}, {@code _1h}, {@code _4h}, {@code _1d} on both markets — the timeframes BR-08
 *       stores;
 *   <li>{@code <symbol>@ticker} on both markets — the last price and the 24-hour statistics, with the best bid
 *       and ask on Spot only;
 *   <li>futures {@code <symbol>@markPrice@1s} — mark price, index price and the predicted funding rate.
 * </ul>
 *
 * <p>Six streams per Spot pair and seven per futures pair (D-43; {@code kline_1m} added by T-043, the futures
 * {@code @ticker} by TR-38 for the trading screen's 24-hour figures). A pair carries three more when
 * {@code trading} is enabled for its market (TR-03, TR-38): {@code <symbol>@aggTrade} (each trade, for the paper
 * engine and the trades panel), {@code <symbol>@bookTicker} (best bid and ask as they change) and
 * {@code <symbol>@depth20@100ms} (the top levels of the order book). On Futures the last two are {@code /public}
 * streams and go on connections of their own ({@link #publicStreamsFor}); the rest are {@code /market} streams.
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
        this.parser = new BinanceStreamParser(json, clock);
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
                MarketInterval.ONE_MINUTE,
                MarketInterval.FIFTEEN_MINUTES,
                MarketInterval.ONE_HOUR,
                MarketInterval.FOUR_HOURS,
                MarketInterval.ONE_DAY)) {
            streams.add(s + "@kline_" + timeframe.code());
        }
        streams.add(s + "@ticker");
        if (venue != BinanceVenue.SPOT) {
            streams.add(s + "@markPrice@1s");
        }
        return List.copyOf(streams);
    }

    /**
     * The streams this client opens for one pair on one venue's main connections: the six of NSF-03 and, when
     * {@code trading} is enabled for the venue, the three of TR-03 on Spot or the trades alone on Futures.
     */
    public List<String> streamsFor(BinanceVenue venue, String symbol) {
        List<String> streams = new ArrayList<>(streamsOf(venue, symbol));
        String s = symbol.toLowerCase(Locale.ROOT);
        if (tradingOn(venue)) {
            streams.add(s + "@aggTrade");
        }
        if (venue == BinanceVenue.SPOT && tradingOn(venue)) {
            streams.addAll(bookStreams(s));
        }
        return List.copyOf(streams);
    }

    /**
     * The streams of one pair the venue serves under the {@code /public} route only: the best bid and ask and the
     * depth of a Futures pair when its trading streams are enabled (TR-38); none on Spot, whose host has no routes.
     */
    public List<String> publicStreamsFor(BinanceVenue venue, String symbol) {
        if (venue == BinanceVenue.SPOT || !tradingOn(venue)) {
            return List.of();
        }
        return bookStreams(symbol.toLowerCase(Locale.ROOT));
    }

    private boolean tradingOn(BinanceVenue venue) {
        BinanceStreamProperties.Trading trading = properties.trading();
        return venue == BinanceVenue.SPOT ? trading.enabled() : trading.futures();
    }

    private List<String> bookStreams(String s) {
        return List.of(s + "@bookTicker", s + "@depth" + properties.trading().depthLevels() + "@100ms");
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

    /** A connection for streams of {@link #publicStreamsFor}, on the Futures {@code /public} route, not yet started. */
    public BinanceStreamShard publicShard(String name, List<String> streams, BinanceStreamShard.Listener listener) {
        return new BinanceStreamShard(
                name,
                properties.trading().futuresPublicUrl(),
                streams,
                http,
                parser,
                timer,
                properties,
                backoff,
                clock,
                listener);
    }

    /** Stops the timer and the HTTP client. Shards still open are aborted with it. */
    @Override
    public void close() {
        timer.shutdownNow();
        http.shutdownNow();
    }
}
