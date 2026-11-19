package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * When each market's stream last delivered data, and since when it has delivered without a break (D-158): the two
 * signals of the health monitor that come from the stream. Written by the stream readers on every message, so a write
 * costs a lock held for two field reads and writes.
 *
 * <p>A break is a silence of at least {@code reconnectingAfter}; the run of live data after it starts anew. Before the
 * first message, both read as the start of the process, so a stream that never connects is paused after
 * {@code pauseAfter}.
 *
 * <p>Once the supervisor has registered a market's price connections, each with its pairs, the market reads as live
 * while enough of its pairs are (E5): its signals are those of the pairs that make up all but {@code pause-ratio} of
 * them, the most recently live first. A few pairs of one silent connection leave it live, and are named by
 * {@link #silentSymbols} once silent {@code pauseAfter}; more than that share silent reads as the market silent, which
 * the monitor pauses. Messages of no registered connection, as the public book streams, count for none of them.
 *
 * <p>Rule: NSF-03; D-158.
 */
@Component
public class MarketLiveness {

    private final Duration breakAfter;
    private final Duration pauseAfter;
    private final double pauseRatio;
    private final Clock clock;
    private final Map<MarketType, Run> runs = new EnumMap<>(MarketType.class);
    private final Map<MarketType, Map<String, Connection>> connections = new EnumMap<>(MarketType.class);

    public MarketLiveness(MarketHealthProperties properties, Clock clock) {
        this.breakAfter = properties.reconnectingAfter();
        this.pauseAfter = properties.pauseAfter();
        this.pauseRatio = properties.pauseRatio();
        this.clock = clock;
        Instant start = clock.instant();
        for (MarketType market : MarketType.values()) {
            runs.put(market, new Run(start));
            connections.put(market, Map.of());
        }
    }

    /**
     * The market's price connections now, each with the symbols it streams; replaces those before, each starting as if
     * it had just delivered.
     */
    public void connections(MarketType market, Map<String, List<String>> symbolsByConnection) {
        Instant now = clock.instant();
        Map<String, Connection> registered = new LinkedHashMap<>();
        symbolsByConnection.forEach((name, symbols) -> {
            if (!symbols.isEmpty()) {
                registered.put(name, new Connection(List.copyOf(symbols), new Run(now)));
            }
        });
        synchronized (connections) {
            connections.put(market, Map.copyOf(registered));
        }
    }

    /** A message of the market arrived at {@code at}, on no registered connection. */
    public void record(MarketType market, Instant at) {
        record(market, null, at);
    }

    /** A message of the market arrived at {@code at} on the named connection, or on an unregistered one if null. */
    public void record(MarketType market, String connection, Instant at) {
        runs.get(market).record(at, breakAfter);
        if (connection == null) {
            return;
        }
        Connection found = registered(market).get(connection);
        if (found != null) {
            found.run().record(at, breakAfter);
        }
    }

    /** When the market's latest live data arrived: of enough of its pairs, once its connections are registered. */
    public Instant lastLiveAt(MarketType market) {
        Map<String, Connection> registered = registered(market);
        if (registered.isEmpty()) {
            return runs.get(market).lastLiveAt();
        }
        List<Connection> latestFirst = new ArrayList<>(registered.values());
        latestFirst.sort(
                Comparator.comparing((Connection c) -> c.run().lastLiveAt()).reversed());
        return enough(latestFirst, total(registered)).run().lastLiveAt();
    }

    /**
     * When the market's current unbroken run of live data began: of enough of its pairs, once its connections are
     * registered; a connection silent for a break has no run.
     */
    public Instant liveSince(MarketType market) {
        Map<String, Connection> registered = registered(market);
        if (registered.isEmpty()) {
            return runs.get(market).liveSince();
        }
        Instant now = clock.instant();
        List<Connection> flowing = new ArrayList<>();
        for (Connection connection : registered.values()) {
            if (Duration.between(connection.run().lastLiveAt(), now).compareTo(breakAfter) < 0) {
                flowing.add(connection);
            }
        }
        int needed = needed(total(registered));
        if (weight(flowing) < needed) {
            return lastLiveAt(market);
        }
        flowing.sort(Comparator.comparing(c -> c.run().liveSince()));
        return enough(flowing, total(registered)).run().liveSince();
    }

    /** The symbols of the market's connections silent at least {@code pauseAfter}. */
    public List<String> silentSymbols(MarketType market) {
        Instant now = clock.instant();
        List<String> silent = new ArrayList<>();
        for (Connection connection : registered(market).values()) {
            if (Duration.between(connection.run().lastLiveAt(), now).compareTo(pauseAfter) >= 0) {
                silent.addAll(connection.symbols());
            }
        }
        silent.sort(Comparator.naturalOrder());
        return List.copyOf(silent);
    }

    private Map<String, Connection> registered(MarketType market) {
        synchronized (connections) {
            return connections.get(market);
        }
    }

    /** The connection, in the given order, at which the pairs so far reach the share that must be live. */
    private Connection enough(List<Connection> ordered, int total) {
        int needed = needed(total);
        int sum = 0;
        for (Connection connection : ordered) {
            sum += connection.symbols().size();
            if (sum >= needed) {
                return connection;
            }
        }
        return ordered.getLast();
    }

    /** How many of {@code total} pairs must be live: all but {@code pause-ratio} of them, at least one. */
    private int needed(int total) {
        return Math.max(1, total - (int) Math.floor(total * pauseRatio));
    }

    private static int total(Map<String, Connection> registered) {
        return weight(registered.values());
    }

    private static int weight(Iterable<Connection> connections) {
        int sum = 0;
        for (Connection connection : connections) {
            sum += connection.symbols().size();
        }
        return sum;
    }

    /** A price connection of a market: the symbols it streams and its run of live data. */
    private record Connection(List<String> symbols, Run run) {}

    private static final class Run {

        private Instant lastLiveAt;
        private Instant liveSince;

        private Run(Instant start) {
            this.lastLiveAt = start;
            this.liveSince = start;
        }

        private synchronized void record(Instant at, Duration breakAfter) {
            if (at.isBefore(lastLiveAt)) {
                return;
            }
            if (Duration.between(lastLiveAt, at).compareTo(breakAfter) >= 0) {
                liveSince = at;
            }
            lastLiveAt = at;
        }

        private synchronized Instant lastLiveAt() {
            return lastLiveAt;
        }

        private synchronized Instant liveSince() {
            return liveSince;
        }
    }
}
