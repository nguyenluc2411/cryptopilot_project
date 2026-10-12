package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.BookTop;
import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.OrderBookDepth;
import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The latest best bid and ask, top of the order book and trades of each pair streamed with the trading streams
 * (TR-03), as the stream last read them.
 *
 * <p>In memory, overwritten by every message, like {@link LatestMarketData}: the stream reader only writes a map entry,
 * and a lost value is refilled by the next message, at most 100 ms later for the depth. A best bid and ask or a depth
 * older than {@code maxAge} is not served as current; the trades are kept, the latest {@code recentTrades} per pair,
 * and served with their own instants.
 *
 * <p>Held in this instance only, not in Redis: the paper engine that reads it runs in the same process, and the plan
 * runs one instance until a broker relay is added (plan section 12.3, P2).
 *
 * <p>Rule: TR-03; plan sections 11.1 and 12.3; BR-07 (a pair no longer streamed is dropped).
 */
@Component
public class TradingMarketData {

    private final Map<Key, BookTop> bookTops = new ConcurrentHashMap<>();
    private final Map<Key, OrderBookDepth> depths = new ConcurrentHashMap<>();
    private final Map<Key, Deque<MarketTrade>> trades = new ConcurrentHashMap<>();
    private final Duration maxAge;
    private final int recentTrades;
    private final Clock clock;

    public TradingMarketData(BinanceStreamProperties properties, Clock clock) {
        this.maxAge = properties.trading().maxAge();
        this.recentTrades = properties.trading().recentTrades();
        this.clock = clock;
    }

    /** Records a best bid and ask, unless one of a later book update is already held. */
    public void recordBookTop(BookTop top) {
        bookTops.merge(key(top.market(), top.pairId()), top, (old, now) -> now.updateId() < old.updateId() ? old : now);
    }

    /** Records a depth snapshot, unless one of a later book update is already held. */
    public void recordDepth(OrderBookDepth depth) {
        depths.merge(
                key(depth.market(), depth.pairId()),
                depth,
                (old, now) -> now.lastUpdateId() < old.lastUpdateId() ? old : now);
    }

    /** Adds a trade to the pair's latest ones, dropping the oldest past {@code recentTrades}. */
    public void recordTrade(MarketTrade trade) {
        Deque<MarketTrade> latest =
                trades.computeIfAbsent(key(trade.market(), trade.pairId()), k -> new ArrayDeque<>());
        synchronized (latest) {
            latest.addFirst(trade);
            while (latest.size() > recentTrades) {
                latest.removeLast();
            }
        }
    }

    /** The pair's best bid and ask, when one no older than {@code maxAge} is held. */
    public Optional<BookTop> currentBookTop(MarketType market, UUID pairId) {
        return Optional.ofNullable(bookTops.get(key(market, pairId))).filter(top -> fresh(top.receivedAt()));
    }

    /** The pair's depth snapshot, when one no older than {@code maxAge} is held. */
    public Optional<OrderBookDepth> currentDepth(MarketType market, UUID pairId) {
        return Optional.ofNullable(depths.get(key(market, pairId))).filter(depth -> fresh(depth.receivedAt()));
    }

    /** The pair's latest trades, newest first, at most {@code limit}. */
    public List<MarketTrade> recentTrades(MarketType market, UUID pairId, int limit) {
        Deque<MarketTrade> latest = trades.get(key(market, pairId));
        if (latest == null) {
            return List.of();
        }
        synchronized (latest) {
            return latest.stream().limit(limit).toList();
        }
    }

    /** Drops the pairs of a market that are no longer streamed (BR-07). */
    public void retainOnly(MarketType market, Collection<UUID> streamed) {
        Set<UUID> keep = Set.copyOf(streamed);
        for (Map<Key, ?> held : List.of(bookTops, depths, trades)) {
            held.keySet().removeIf(key -> key.market() == market && !keep.contains(key.pairId()));
        }
    }

    private boolean fresh(Instant at) {
        return !at.isBefore(clock.instant().minus(maxAge));
    }

    private static Key key(MarketType market, UUID pairId) {
        return new Key(market, pairId);
    }

    private record Key(MarketType market, UUID pairId) {}
}
