package com.cryptopilot.market.service;

import com.cryptopilot.market.client.StubExchange.Answer;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The exchange's 5-minute open interest and long/short histories, and its settled funding rates, answered from
 * the query the way Binance answers them (checked against the live endpoints on 2026-09-25): entries on the
 * 5-minute instants inside {@code [startTime, endTime]}, oldest first, at most {@code limit}; with no range, the
 * latest {@code limit}. Nothing after {@link #publishedUntil} exists yet.
 *
 * <p>Settled funding rates follow a per-symbol schedule of instants, each stamped a few milliseconds late as
 * the exchange does, and page forward from {@code startTime}.
 */
final class SyntheticFuturesMetrics {

    static final Duration PERIOD = Duration.ofMinutes(5);

    private volatile Instant publishedUntil;
    private final Map<String, List<String>> settlements = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

    SyntheticFuturesMetrics(Instant publishedUntil) {
        this.publishedUntil = publishedUntil;
    }

    /** The last instant whose readings the exchange has published. */
    void publishedUntil(Instant instant) {
        this.publishedUntil = instant;
    }

    /** The open interest of a reading: its period number, with twelve decimals the column must keep. */
    static String openInterest(Instant at) {
        return (at.toEpochMilli() / PERIOD.toMillis() % 100_000) + ".123456789012";
    }

    /** The open interest value of a reading, with eight decimals. */
    static String openInterestValue(Instant at) {
        return (at.toEpochMilli() / PERIOD.toMillis() % 100_000) + "000.12345678";
    }

    Answer openInterestHist(URI uri) {
        return periods(
                uri,
                "openInterest",
                (symbol, at) -> String.format(
                        "{\"symbol\":\"%s\",\"sumOpenInterest\":\"%s\",\"sumOpenInterestValue\":\"%s\","
                                + "\"CMCCirculatingSupply\":\"20088518.00000000\",\"timestamp\":%d}",
                        symbol, openInterest(at), openInterestValue(at), at.toEpochMilli()));
    }

    Answer longShortRatio(URI uri) {
        return periods(
                uri,
                "longShort",
                (symbol, at) -> String.format(
                        "{\"symbol\":\"%s\",\"longAccount\":\"0.5525\",\"longShortRatio\":\"1.2346\",\"shortAccount\":\"0.4475\","
                                + "\"timestamp\":%d}",
                        symbol, at.toEpochMilli()));
    }

    /**
     * Settlements of a symbol, each an entry {@code fundingTime,rate,markPrice} with the mark price possibly
     * empty; they must be given oldest first.
     */
    void settlements(String symbol, String... entries) {
        settlements.put(symbol, List.of(entries));
    }

    /** Hourly settlements of a symbol from {@code first}, {@code count} of them, each a millisecond late. */
    void hourlySettlements(String symbol, Instant first, int count) {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(first.plus(Duration.ofHours(i)).plusMillis(1).toEpochMilli() + ",0.00010000,100.5");
        }
        settlements.put(symbol, entries);
    }

    Answer fundingRate(URI uri) {
        Map<String, String> query = query(uri);
        String symbol = query.get("symbol");
        count(symbol + " fundingRate");
        long start = Long.parseLong(query.getOrDefault("startTime", "0"));
        int limit = Integer.parseInt(query.getOrDefault("limit", "100"));
        List<String> body = new ArrayList<>();
        for (String entry : settlements.getOrDefault(symbol, List.of())) {
            String[] fields = entry.split(",", -1);
            long time = Long.parseLong(fields[0]);
            if (time >= start && body.size() < limit) {
                body.add(String.format(
                        "{\"symbol\":\"%s\",\"fundingTime\":%d,\"fundingRate\":\"%s\",\"markPrice\":\"%s\","
                                + "\"rateType\":\"Regular\"}",
                        symbol, time, fields[1], fields[2]));
            }
        }
        return Answer.ok("[" + String.join(",", body) + "]");
    }

    /** How many requests of a kind ({@code SYMBOL openInterest}, {@code SYMBOL longShort}, ...) were answered. */
    int requests(String key) {
        return requests.getOrDefault(key, new AtomicInteger()).get();
    }

    private Answer periods(URI uri, String kind, Entry entry) {
        Map<String, String> query = query(uri);
        String symbol = query.get("symbol");
        count(symbol + " " + kind);
        int limit = Integer.parseInt(query.getOrDefault("limit", "30"));
        long step = PERIOD.toMillis();
        Instant last = Instant.ofEpochMilli(Math.floorDiv(publishedUntil.toEpochMilli(), step) * step);
        List<Instant> instants = new ArrayList<>();
        if (query.containsKey("startTime") && query.containsKey("endTime")) {
            long start = Math.ceilDiv(Long.parseLong(query.get("startTime")), step) * step;
            long end = Long.parseLong(query.get("endTime"));
            for (long t = start;
                    t <= end && !Instant.ofEpochMilli(t).isAfter(last) && instants.size() < limit;
                    t += step) {
                instants.add(Instant.ofEpochMilli(t));
            }
        } else {
            for (int i = limit - 1; i >= 0; i--) {
                instants.add(last.minus(PERIOD.multipliedBy(i)));
            }
        }
        List<String> body = new ArrayList<>();
        for (Instant at : instants) {
            body.add(entry.json(symbol, at));
        }
        return Answer.ok("[" + String.join(",", body) + "]");
    }

    private void count(String key) {
        requests.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> query = new HashMap<>();
        if (uri.getQuery() != null) {
            for (String pair : uri.getQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                query.put(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        return query;
    }

    @FunctionalInterface
    private interface Entry {
        String json(String symbol, Instant at);
    }
}
