package com.cryptopilot.market.job;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceStreamClient;
import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.client.BinanceStreamShard;
import com.cryptopilot.market.client.BinanceVenue;
import com.cryptopilot.market.client.StreamMessage;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.event.MarketStreamReconnected;
import com.cryptopilot.market.event.SymbolsSynchronised;
import com.cryptopilot.market.service.LatestMarketData;
import com.cryptopilot.market.service.StreamCandleService;
import com.cryptopilot.market.service.StreamTarget;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * five streams, packed into connections of at most {@code maxStreamsPerConnection} streams (100; TECHNICAL_DESIGN
 * 7.1 step 1), a pair's streams never split across two. The pairs are read again after every symbol
 * synchronisation of the market and every {@code refreshInterval}; when the set has changed, that market's
 * connections are replaced, and a pair no longer streamed is dropped from the latest prices (BR-07). An unchanged
 * set touches nothing.
 *
 * <h2>Routing, without blocking a reader</h2>
 *
 * <ul>
 *   <li>A closed candle of a stored timeframe goes to the {@link ClosedCandlePipeline}; a forming one is not
 *       stored (BR-08).
 *   <li>A Spot ticker or a futures mark price overwrites the pair's entry in {@link LatestMarketData}.
 *   <li>A connection that opens again after a loss publishes {@link MarketStreamReconnected}, and NSF-02
 *       fetches whatever closed meanwhile.
 * </ul>
 *
 * <p>Off unless {@code cryptopilot.market.stream.enabled}; on in the {@code dev} and {@code prod} profiles.
 *
 * <p>Rule: NSF-03; BR-07, BR-08; TECHNICAL_DESIGN 7.1 steps 1, 2 and 6, 10.
 */
@Component
public class MarketStreamSupervisor {

    private static final Logger log = LoggerFactory.getLogger(MarketStreamSupervisor.class);

    private final BinanceStreamClient streams;
    private final StreamCandleService candles;
    private final LatestMarketData latest;
    private final ClosedCandlePipeline pipeline;
    private final TaskScheduler scheduler;
    private final BinanceStreamProperties properties;
    private final ApplicationEventPublisher events;
    private final Map<MarketType, MarketStreams> running = new EnumMap<>(MarketType.class);

    public MarketStreamSupervisor(
            BinanceStreamClient streams,
            StreamCandleService candles,
            LatestMarketData latest,
            ClosedCandlePipeline pipeline,
            TaskScheduler scheduler,
            BinanceStreamProperties properties,
            ApplicationEventPublisher events) {
        this.streams = streams;
        this.candles = candles;
        this.latest = latest;
        this.pipeline = pipeline;
        this.scheduler = scheduler;
        this.properties = properties;
        this.events = events;
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
     * Makes the market's connections match its pairs: replaced when the set changed, untouched otherwise.
     *
     * @return whether the connections were replaced
     */
    public synchronized boolean refresh(MarketType market) {
        List<StreamTarget> targets = candles.targets(market);
        MarketStreams current = running.get(market);
        if (current != null && new HashSet<>(current.targets()).equals(new HashSet<>(targets))) {
            return false;
        }
        if (current != null) {
            current.shards().forEach(BinanceStreamShard::close);
        }
        latest.retainOnly(market, targets.stream().map(StreamTarget::pairId).toList());
        Map<String, StreamTarget> bySymbol =
                targets.stream().collect(Collectors.toMap(StreamTarget::symbol, Function.identity()));
        List<BinanceStreamShard> shards = shardsFor(market, targets, bySymbol);
        running.put(market, new MarketStreams(targets, shards));
        shards.forEach(BinanceStreamShard::start);
        log.info("NSF-03 {}: {} pairs on {} connections", market, targets.size(), shards.size());
        return true;
    }

    /** The connections open now for a market. */
    public synchronized List<BinanceStreamShard> shards(MarketType market) {
        MarketStreams current = running.get(market);
        return current == null ? List.of() : current.shards();
    }

    /** Closes every connection and stops the pipeline. */
    @PreDestroy
    public synchronized void stop() {
        running.values().forEach(market -> market.shards().forEach(BinanceStreamShard::close));
        running.clear();
        pipeline.stop();
    }

    private List<BinanceStreamShard> shardsFor(
            MarketType market, List<StreamTarget> targets, Map<String, StreamTarget> bySymbol) {
        BinanceVenue venue = market == MarketType.SPOT ? BinanceVenue.SPOT : BinanceVenue.USD_M_FUTURES;
        BinanceStreamShard.Listener listener = new Router(market, bySymbol);
        List<BinanceStreamShard> shards = new ArrayList<>();
        List<String> batch = new ArrayList<>();
        for (StreamTarget target : targets) {
            List<String> names = BinanceStreamClient.streamsOf(venue, target.symbol());
            if (batch.size() + names.size() > streams.maxStreamsPerConnection()) {
                shards.add(streams.shard(venue, market + "-" + shards.size(), batch, listener));
                batch = new ArrayList<>();
            }
            batch.addAll(names);
        }
        if (!batch.isEmpty()) {
            shards.add(streams.shard(venue, market + "-" + shards.size(), batch, listener));
        }
        return shards;
    }

    /** What one market streams now. */
    private record MarketStreams(List<StreamTarget> targets, List<BinanceStreamShard> shards) {}

    /** Routes the messages of one market's connections; called on the readers' threads, never blocks. */
    private final class Router implements BinanceStreamShard.Listener {

        private final MarketType market;
        private final Map<String, StreamTarget> bySymbol;

        private Router(MarketType market, Map<String, StreamTarget> bySymbol) {
            this.market = market;
            this.bySymbol = Map.copyOf(bySymbol);
        }

        @Override
        public void onMessage(StreamMessage message) {
            StreamTarget target = bySymbol.get(message.symbol());
            if (target == null) {
                return;
            }
            switch (message) {
                case KlineMessage kline -> {
                    if (kline.closed()) {
                        pipeline.submit(market, target, kline);
                    }
                }
                case TickerMessage ticker -> latest.recordTicker(target.pairId(), ticker);
                case MarkPriceMessage markPrice -> latest.recordMarkPrice(target.pairId(), markPrice);
            }
        }

        @Override
        public void onConnected(BinanceStreamShard shard, boolean afterLoss) {
            if (afterLoss) {
                events.publishEvent(new MarketStreamReconnected(market));
            }
        }
    }
}
