package com.cryptopilot.market.service;

import com.cryptopilot.market.client.StubExchange.Answer;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An exchange's klines endpoint in miniature: answers {@code symbol}, {@code interval}, {@code startTime},
 * {@code endTime} and {@code limit} as Binance does — candles aligned to the interval from the epoch, oldest
 * first, starting at the first candle at or after {@code startTime} (or at the listing, if later), at most
 * {@code limit}, and ending with the last one opened at or before {@code endTime} when given, else with the
 * candle that is still forming at {@code now}.
 *
 * <p>Every candle carries the same prices: an open of {@code 0.00000001} and a close of
 * {@code 123456.12345678}, so a test can check that the smallest and the longest decimals the exchange sends
 * survive to the database unchanged. The weight header is configurable, to exercise the backfill's pacing.
 */
public final class SyntheticKlines {

    private final Instant now;
    private final Map<String, Instant> listedAt = new HashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private volatile int usedWeight = 2;

    public SyntheticKlines(Instant now) {
        this.now = now;
    }

    /** A symbol first listed at this instant; before it the exchange has no candle. */
    public SyntheticKlines listed(String symbol, Instant at) {
        listedAt.put(symbol, at);
        return this;
    }

    /** The weight every response reports as used this minute. */
    public SyntheticKlines usedWeight(int weight) {
        this.usedWeight = weight;
        return this;
    }

    public int requests() {
        return requests.get();
    }

    /** How many requests asked for this symbol and interval, e.g. {@code "BTCUSDT 1h"}. */
    public long requests(String symbolAndInterval) {
        return log.stream().filter(symbolAndInterval::equals).count();
    }

    /** Every symbol asked for. */
    public List<String> symbolsRequested() {
        return log.stream().map(entry -> entry.split(" ")[0]).distinct().toList();
    }

    public Answer answer(URI request) {
        requests.incrementAndGet();
        Map<String, String> query = query(request);
        String symbol = query.get("symbol");
        log.add(symbol + " " + query.get("interval"));
        Duration interval = intervalOf(query.get("interval"));
        long step = interval.toMillis();
        long start = Long.parseLong(query.get("startTime"));
        int limit = Integer.parseInt(query.get("limit"));
        long end = query.containsKey("endTime")
                ? Math.min(Long.parseLong(query.get("endTime")), now.toEpochMilli())
                : now.toEpochMilli();
        long listed = listedAt.getOrDefault(symbol, Instant.EPOCH).toEpochMilli();
        long first = Math.max(ceilTo(start, step), ceilTo(listed, step));

        StringBuilder body = new StringBuilder("[");
        int count = 0;
        for (long open = first; open <= end && count < limit; open += step, count++) {
            if (count > 0) {
                body.append(',');
            }
            body.append('[')
                    .append(open)
                    .append(",\"0.00000001\",\"123456.12345678\",\"0.00000001\",\"123456.12345678\",\"12.00000001\",")
                    .append(open + step - 1)
                    .append(",\"756600.12345678\",1234,\"6.0\",\"378300.0\",\"0\"]");
        }
        body.append(']');
        return Answer.ok(body.toString()).withHeader("X-MBX-USED-WEIGHT-1M", Integer.toString(usedWeight));
    }

    /** The open time of the candle forming at {@code now}. */
    public Instant formingOpen(Duration interval) {
        long step = interval.toMillis();
        return Instant.ofEpochMilli(now.toEpochMilli() / step * step);
    }

    static Duration intervalOf(String code) {
        return switch (code) {
            case "15m" -> Duration.ofMinutes(15);
            case "1h" -> Duration.ofHours(1);
            case "4h" -> Duration.ofHours(4);
            case "1d" -> Duration.ofDays(1);
            default -> throw new IllegalArgumentException(code);
        };
    }

    private static long ceilTo(long millis, long step) {
        return (millis + step - 1) / step * step;
    }

    private static Map<String, String> query(URI request) {
        Map<String, String> query = new HashMap<>();
        for (String part : request.getQuery().split("&")) {
            String[] pair = part.split("=", 2);
            query.put(pair[0], pair[1]);
        }
        return query;
    }
}
