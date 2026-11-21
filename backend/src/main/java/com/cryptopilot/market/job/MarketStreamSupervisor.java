package com.cryptopilot.market.job;

import com.cryptopilot.market.BookTop;
import com.cryptopilot.market.MarkPrice;
import com.cryptopilot.market.MarkPriceListener;
import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.MarketTradeListener;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineListener;
import com.cryptopilot.market.OrderBookDepth;
import com.cryptopilot.market.client.BinanceStreamClient;
import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.client.BinanceStreamShard;
import com.cryptopilot.market.client.StreamMessage;
import com.cryptopilot.market.client.StreamMessage.AggTradeMessage;
import com.cryptopilot.market.client.StreamMessage.BookTickerMessage;
import com.cryptopilot.market.client.StreamMessage.DepthMessage;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.client.StreamMessage.Level;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.event.MarketStreamReconnected;
import com.cryptopilot.market.event.PairMarketEnabled;
import com.cryptopilot.market.event.SymbolsSynchronised;
import com.cryptopilot.market.model.StreamTarget;
import com.cryptopilot.market.model.enums.BinanceVenue;
import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketUpdateService;
import com.cryptopilot.market.service.StreamCandleService;
import com.cryptopilot.market.service.impl.LatestMarketData;
import com.cryptopilot.market.service.impl.MarketLiveness;
import com.cryptopilot.market.service.impl.TradingMarketData;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Runs NSF-03: keeps one set of stream connections per market open for the pairs that should be streamed, and
 * routes what they read.
 *
 * <h2>Which connections</h2>
 *
 * <p>The pairs of {@link StreamCandleService#targets} — enabled by an administrator and trading — each with its
 * six streams, packed into connections of at most {@code maxStreamsPerConnection} streams (100; TECHNICAL_DESIGN
 * 7.1 step 1), a pair's streams never split across two. The pairs are read again after every symbol
 * synchronisation of the market and every {@code refreshInterval}. An unchanged set touches nothing. A changed one
 * touches only what it must: a connection carrying a pair no longer streamed is replaced by one carrying the rest of
 * its pairs, a pair newly streamed goes on a new connection, and every other connection stays open, so the prices of
 * the other pairs never stop for a change that is not theirs; a pair no longer streamed is dropped from the latest
 * prices (BR-07). So that pairs added one batch at a time do not leave a trail of small connections, a batch joins the
 * connection a previous batch was given when it has room: that connection is replaced by one carrying both, which
 * interrupts only the pairs added before it, never a connection opened at start-up. A Futures pair's best bid and ask and depth (TR-38) go on connections of their own, on the
 * exchange's {@code /public} route; everything else of the market shares the {@code /market} ones.
 *
 * <p>Pairs an administrator turns on one after the other are opened together, {@link #ENABLE_SETTLE} after the first:
 * one refresh, one new connection, rather than one of each per pair.
 *
 * <h2>Routing, without blocking a reader</h2>
 *
 * <ul>
 *   <li>A 1-minute candle, forming or closed, goes to every {@link MinuteKlineListener} (the matching engine); the
 *       latest closed one of each pair is also held in {@link TradingMarketData}. It is not stored or broadcast.
 *   <li>A futures mark price is cached and goes to every {@link MarkPriceListener} (the matching engine's liquidation
 *       book), from the same {@code markPrice@1s} stream; no second stream is opened.
 *   <li>A closed candle of a stored timeframe goes to the {@link ClosedCandlePipeline}; a forming one is not
 *       stored (BR-08).
 *   <li>A Spot ticker or a futures mark price overwrites the pair's entry in {@link LatestMarketData}.
 *   <li>A trade, a best bid and ask or a depth snapshot of the trading streams (TR-03) overwrites the pair's entry in
 *       {@link TradingMarketData}; a trade also goes to every {@link MarketTradeListener} (the paper engine).
 *   <li>A price connection that opens again after a loss publishes {@link MarketStreamReconnected}, and NSF-02
 *       fetches whatever closed meanwhile. A book connection (TR-38) carries no candle, so its losses, which the
 *       exchange causes often, announce nothing.
 * </ul>
 *
 * <p>Off unless {@code cryptopilot.market.stream.enabled}; on in the {@code dev} and {@code prod} profiles.
 *
 * <p>Rule: NSF-03; BR-07, BR-08; TECHNICAL_DESIGN 7.1 steps 1, 2 and 6, 10.
 */
@Component
public class MarketStreamSupervisor {

    private static final Logger log = LoggerFactory.getLogger(MarketStreamSupervisor.class);

    /** How long after a pair is turned on its market is refreshed, so the pairs of a batch share the refresh. */
    static final Duration ENABLE_SETTLE = Duration.ofSeconds(2);

    private final BinanceStreamClient streams;
    private final StreamCandleService candles;
    private final LatestMarketData latest;
    private final ClosedCandlePipeline pipeline;
    private final TaskScheduler scheduler;
    private final BinanceStreamProperties properties;
    private final ApplicationEventPublisher events;
    private final MarketUpdateService updates;
    private final List<MinuteKlineListener> minuteListeners;
    private final TradingMarketData trading;
    private final List<MarketTradeListener> tradeListeners;
    private final List<MarkPriceListener> markListeners;
    private final Clock clock;
    private final MarketLiveness liveness;
    private final Map<MarketType, MarketStreams> running = new EnumMap<>(MarketType.class);
    /** Per market, the number of the next connection opened; names are never reused while the process runs. */
    private final Map<MarketType, Integer> nextConnection = new EnumMap<>(MarketType.class);
    /** Per market, whether a refresh for pairs turned on is scheduled and not yet started. */
    private final Map<MarketType, AtomicBoolean> enableQueued = new EnumMap<>(MarketType.class);

    public MarketStreamSupervisor(
            BinanceStreamClient streams,
            StreamCandleService candles,
            LatestMarketData latest,
            ClosedCandlePipeline pipeline,
            TaskScheduler scheduler,
            BinanceStreamProperties properties,
            ApplicationEventPublisher events,
            MarketUpdateService updates,
            List<MinuteKlineListener> minuteListeners,
            TradingMarketData trading,
            List<MarketTradeListener> tradeListeners,
            List<MarkPriceListener> markListeners,
            Clock clock,
            MarketLiveness liveness) {
        this.streams = streams;
        this.candles = candles;
        this.latest = latest;
        this.pipeline = pipeline;
        this.scheduler = scheduler;
        this.properties = properties;
        this.events = events;
        this.updates = updates;
        this.minuteListeners = List.copyOf(minuteListeners);
        this.trading = trading;
        this.tradeListeners = List.copyOf(tradeListeners);
        this.markListeners = List.copyOf(markListeners);
        this.clock = clock;
        this.liveness = liveness;
        for (MarketType market : MarketType.values()) {
            nextConnection.put(market, 0);
            enableQueued.put(market, new AtomicBoolean(false));
        }
    }

    /** Opens the streams of both markets and schedules the refresh, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("NSF-03 market streams are disabled");
            return;
        }
        pipeline.start();
        refreshAll();
        scheduler.scheduleWithFixedDelay(this::refreshAll, properties.refreshInterval());
        log.info(
                "NSF-03 started; pairs re-read every {} and after each symbol synchronisation",
                properties.refreshInterval());
    }

    /** A market's exchange statuses have changed: re-read its pairs. */
    @EventListener
    public void onSymbolsSynchronised(SymbolsSynchronised event) {
        if (properties.enabled()) {
            refresh(event.market());
        }
    }

    /**
     * An administrator brought a pair into service: its streams open within {@link #ENABLE_SETTLE}, not at the next
     * refresh, together with the pairs turned on meanwhile. After commit, so the pair is read as saved, and on the
     * scheduler, so the administrator's answer does not wait for the connection.
     */
    @EventListener
    public void onPairMarketEnabled(PairMarketEnabled event) {
        if (!properties.enabled()) {
            return;
        }
        AfterCommit.run(() -> {
            AtomicBoolean queued = enableQueued.get(event.market());
            if (!queued.compareAndSet(false, true)) {
                return;
            }
            try {
                scheduler.schedule(
                        () -> {
                            queued.set(false);
                            refreshFor(event.market());
                        },
                        clock.instant().plus(ENABLE_SETTLE));
            } catch (RuntimeException refused) {
                queued.set(false);
                // Shutting down: the next refresh opens the streams anyway.
                log.warn("NSF-03 {} could not schedule {}: {}", event.market(), event.symbol(), refused.toString());
            }
        });
    }

    private void refreshFor(MarketType market) {
        try {
            refresh(market);
        } catch (RuntimeException failure) {
            log.error("NSF-03 {} could not open the pairs turned on; the next refresh tries again", market, failure);
        }
    }

    /** Re-reads the pairs of both markets, each on its own. */
    public void refreshAll() {
        for (MarketType market : MarketType.values()) {
            try {
                refresh(market);
            } catch (RuntimeException failure) {
                log.error("NSF-03 {} could not re-read its pairs; the streams stay as they are", market, failure);
            }
        }
    }

    /**
     * Makes the market's connections match its pairs, touching only the connections the change concerns: one carrying a
     * pair no longer streamed is replaced by one carrying the rest of its pairs, the pairs newly streamed go on new
     * connections, and every other connection stays open. An unchanged set touches nothing.
     *
     * @return whether any connection was opened or closed
     */
    public synchronized boolean refresh(MarketType market) {
        List<StreamTarget> targets = candles.targets(market);
        MarketStreams current = running.get(market);
        Set<StreamTarget> wanted = new LinkedHashSet<>(targets);
        if (current != null && new HashSet<>(current.targets()).equals(wanted)) {
            return false;
        }
        List<Connection> kept = new ArrayList<>();
        List<Connection> closed = new ArrayList<>();
        for (Connection connection : current == null ? List.<Connection>of() : current.connections()) {
            (wanted.containsAll(connection.targets()) ? kept : closed).add(connection);
        }
        closed.forEach(connection -> connection.shard().close());
        latest.retainOnly(market, targets.stream().map(StreamTarget::pairId).toList());
        trading.retainOnly(market, targets.stream().map(StreamTarget::pairId).toList());
        List<Connection> opened = new ArrayList<>();
        boolean addition = current != null;
        for (boolean book : new boolean[] {false, true}) {
            Set<StreamTarget> covered = kept.stream()
                    .filter(connection -> connection.book() == book)
                    .flatMap(connection -> connection.targets().stream())
                    .collect(Collectors.toSet());
            List<StreamTarget> missing =
                    targets.stream().filter(target -> !covered.contains(target)).toList();
            if (addition && !missing.isEmpty()) {
                Connection joined = joinable(market, kept, missing, book);
                if (joined != null) {
                    kept.remove(joined);
                    closed.add(joined);
                    joined.shard().close();
                    List<StreamTarget> both = new ArrayList<>(joined.targets());
                    both.addAll(missing);
                    missing = both;
                }
            }
            opened.addAll(open(market, missing, book, addition));
        }
        List<Connection> connections = new ArrayList<>(kept);
        connections.addAll(opened);
        running.put(market, new MarketStreams(targets, List.copyOf(connections)));
        // D-159 E5: each price connection is watched on its own, with its pairs, for the market health.
        Map<String, List<String>> symbolsByConnection = new LinkedHashMap<>();
        connections.stream()
                .filter(connection -> !connection.book())
                .forEach(connection -> symbolsByConnection.put(
                        connection.name(),
                        connection.targets().stream().map(StreamTarget::symbol).toList()));
        liveness.connections(market, symbolsByConnection);
        opened.forEach(connection -> connection.shard().start());
        log.info(
                "NSF-03 {}: {} pairs on {} connections ({} kept, {} closed, {} opened)",
                market,
                targets.size(),
                connections.size(),
                kept.size(),
                closed.size(),
                opened.size());
        return !closed.isEmpty() || !opened.isEmpty();
    }

    /** The connections open now for a market. */
    public synchronized List<BinanceStreamShard> shards(MarketType market) {
        MarketStreams current = running.get(market);
        return current == null
                ? List.of()
                : current.connections().stream().map(Connection::shard).toList();
    }

    /** Closes every connection and stops the pipeline. */
    @PreDestroy
    public synchronized void stop() {
        running.values().forEach(market -> market.connections()
                .forEach(connection -> connection.shard().close()));
        running.clear();
        pipeline.stop();
    }

    /**
     * The connection of a previous addition on this route that has room for the streams of {@code missing}, the
     * emptiest if several; null when none has. A connection opened at start-up never is.
     */
    private Connection joinable(MarketType market, List<Connection> kept, List<StreamTarget> missing, boolean book) {
        int needed = missing.stream()
                .mapToInt(target -> namesOf(market, target, book).size())
                .sum();
        int max = book ? streams.maxPublicStreamsPerConnection() : streams.maxStreamsPerConnection();
        Connection emptiest = null;
        for (Connection connection : kept) {
            int size = connection.shard().streams().size();
            if (connection.book() == book
                    && connection.added()
                    && size + needed <= max
                    && (emptiest == null || size < emptiest.shard().streams().size())) {
                emptiest = connection;
            }
        }
        return emptiest;
    }

    private List<String> namesOf(MarketType market, StreamTarget target, boolean book) {
        BinanceVenue venue = market == MarketType.SPOT ? BinanceVenue.SPOT : BinanceVenue.USD_M_FUTURES;
        return book ? streams.publicStreamsFor(venue, target.symbol()) : streams.streamsFor(venue, target.symbol());
    }

    /**
     * New connections for the targets on one route, not started: the price route, or the book route TR-38 serves under
     * {@code /public} only; {@code added} when they are opened for pairs added after the first opening.
     */
    private List<Connection> open(MarketType market, List<StreamTarget> targets, boolean book, boolean added) {
        BinanceVenue venue = market == MarketType.SPOT ? BinanceVenue.SPOT : BinanceVenue.USD_M_FUTURES;
        List<Connection> opened = new ArrayList<>();
        pack(
                targets,
                target -> namesOf(market, target, book),
                book ? streams.maxPublicStreamsPerConnection() : streams.maxStreamsPerConnection(),
                (batch, batchTargets) -> {
                    int number = nextConnection.merge(market, 1, Integer::sum) - 1;
                    String name = market + (book ? "-PUBLIC-" : "-") + number;
                    Map<String, StreamTarget> bySymbol =
                            batchTargets.stream().collect(Collectors.toMap(StreamTarget::symbol, Function.identity()));
                    BinanceStreamShard shard = book
                            ? streams.publicShard(name, batch, new Router(market, bySymbol, null))
                            : streams.shard(venue, name, batch, new Router(market, bySymbol, name));
                    opened.add(new Connection(shard, name, List.copyOf(batchTargets), book, added));
                });
        return opened;
    }

    /**
     * Packs the streams of the targets into batches of at most one connection's, a pair's never split; each batch is
     * opened with its pairs.
     */
    private void pack(
            List<StreamTarget> targets,
            Function<StreamTarget, List<String>> namesOf,
            int maxStreams,
            BiConsumer<List<String>, List<StreamTarget>> open) {
        List<String> batch = new ArrayList<>();
        List<StreamTarget> batchTargets = new ArrayList<>();
        for (StreamTarget target : targets) {
            List<String> names = namesOf.apply(target);
            if (names.isEmpty()) {
                continue;
            }
            if (!batch.isEmpty() && batch.size() + names.size() > maxStreams) {
                open.accept(batch, batchTargets);
                batch = new ArrayList<>();
                batchTargets = new ArrayList<>();
            }
            batch.addAll(names);
            batchTargets.add(target);
        }
        if (!batch.isEmpty()) {
            open.accept(batch, batchTargets);
        }
    }

    /** What one market streams now. */
    private record MarketStreams(List<StreamTarget> targets, List<Connection> connections) {}

    /**
     * One connection of a market.
     *
     * @param name its name in the log and the market health
     * @param targets the pairs whose streams it carries
     * @param book whether it is a book connection of TR-38 (no candle on it) rather than a price one
     * @param added whether it was opened for pairs added after the first opening, so a later addition may join it
     */
    private record Connection(
            BinanceStreamShard shard, String name, List<StreamTarget> targets, boolean book, boolean added) {}

    /** Routes the messages of one market's connections; called on the readers' threads, never blocks. */
    private final class Router implements BinanceStreamShard.Listener {

        private final MarketType market;
        private final Map<String, StreamTarget> bySymbol;
        private final String connection;

        /** @param connection the price connection it routes, as the liveness knows it; null for the book streams */
        private Router(MarketType market, Map<String, StreamTarget> bySymbol, String connection) {
            this.market = market;
            this.bySymbol = Map.copyOf(bySymbol);
            this.connection = connection;
        }

        @Override
        public void onMessage(StreamMessage message) {
            // D-159: any message proves the market's stream is live, a pair's or not, and its connection's pairs.
            liveness.record(market, connection, clock.instant());
            StreamTarget target = bySymbol.get(message.symbol());
            if (target == null) {
                return;
            }
            if (message instanceof KlineMessage kline && kline.interval() == MarketInterval.ONE_MINUTE) {
                toMinuteListeners(target, kline);
                return;
            }
            switch (message) {
                case KlineMessage kline -> {
                    if (kline.closed()) {
                        pipeline.submit(market, target, kline);
                    }
                }
                case TickerMessage ticker -> {
                    // The Spot snapshots (spot_market_data) are kept by pair: a futures ticker of the same pair,
                    // read for the trading screen's 24-hour figures only, must not take a Spot one's place.
                    if (market == MarketType.SPOT) {
                        latest.recordTicker(target.pairId(), ticker);
                    }
                }
                case MarkPriceMessage markPrice -> {
                    latest.recordMarkPrice(target.pairId(), markPrice);
                    toMarkListeners(target, markPrice);
                }
                case AggTradeMessage aggTrade -> {
                    MarketTrade trade = trade(target, aggTrade);
                    trading.recordTrade(trade);
                    toTradeListeners(trade);
                }
                case BookTickerMessage book ->
                    trading.recordBookTop(new BookTop(
                            market,
                            target.pairId(),
                            book.updateId(),
                            book.bidPrice(),
                            book.bidQuantity(),
                            book.askPrice(),
                            book.askQuantity(),
                            book.eventTime()));
                case DepthMessage depth ->
                    trading.recordDepth(new OrderBookDepth(
                            market,
                            target.pairId(),
                            depth.lastUpdateId(),
                            levels(depth.bids()),
                            levels(depth.asks()),
                            depth.eventTime()));
            }
            // T-022: the cache and the market topics, after storage and on this thread without I/O; whatever they do,
            // the candle above is already handed to storage and the next message is read.
            try {
                updates.onUpdate(market, message);
            } catch (RuntimeException failure) {
                log.warn("NSF-03 {} realtime update failed: {}", market, failure.toString());
            }
        }

        private MarketTrade trade(StreamTarget target, AggTradeMessage message) {
            return new MarketTrade(
                    market,
                    target.pairId(),
                    message.aggregateTradeId(),
                    message.price(),
                    message.quantity(),
                    message.tradeTime(),
                    message.buyerMaker(),
                    message.eventTime());
        }

        private static List<OrderBookDepth.PriceLevel> levels(List<Level> levels) {
            return levels.stream()
                    .map(level -> new OrderBookDepth.PriceLevel(level.price(), level.quantity()))
                    .toList();
        }

        private void toTradeListeners(MarketTrade trade) {
            for (MarketTradeListener listener : tradeListeners) {
                try {
                    listener.onTrade(trade);
                } catch (RuntimeException failure) {
                    log.warn("NSF-03 {} trade listener failed: {}", market, failure.toString());
                }
            }
        }

        private void toMarkListeners(StreamTarget target, MarkPriceMessage markPrice) {
            MarkPrice update = new MarkPrice(target.pairId(), markPrice.markPrice(), markPrice.eventTime());
            for (MarkPriceListener listener : markListeners) {
                try {
                    listener.onMarkPrice(update);
                } catch (RuntimeException failure) {
                    log.warn("NSF-03 {} mark price listener failed: {}", market, failure.toString());
                }
            }
        }

        private void toMinuteListeners(StreamTarget target, KlineMessage kline) {
            MinuteKline update = new MinuteKline(
                    market,
                    target.pairId(),
                    kline.kline().openTime(),
                    kline.kline().low(),
                    kline.kline().high(),
                    kline.kline().close(),
                    kline.closed(),
                    kline.eventTime(),
                    kline.kline().open());
            trading.recordClosedMinute(update);
            for (MinuteKlineListener listener : minuteListeners) {
                try {
                    listener.onMinuteKline(update);
                } catch (RuntimeException failure) {
                    log.warn("NSF-03 {} 1m candle listener failed: {}", market, failure.toString());
                }
            }
        }

        /** A price connection back after a loss announces it, for NSF-02; a book one carries no candle to fetch. */
        @Override
        public void onConnected(BinanceStreamShard shard, boolean afterLoss) {
            if (afterLoss && connection != null) {
                events.publishEvent(new MarketStreamReconnected(market));
            }
        }
    }
}
