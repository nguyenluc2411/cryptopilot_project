package com.cryptopilot.market.model;

import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A scripted price path of one pair (RF-07), read from {@code classpath:market/replay/<name>.json}: the price at a few
 * instants after the start, joined by straight lines, then held at the last one until the replay is stopped.
 *
 * <p>A demonstration chains them: a flat scenario holds the pair where the demonstration opens its position, and a
 * moving one started on the same pair takes over from there (DS-03a, DS-03b, DS-06a).
 *
 * @param name the file's name without {@code .json}, lower case, digits and underscores
 * @param description what the scenario shows, for the console
 * @param market the market whose pair it drives
 * @param symbol the pair, e.g. {@code BTCUSDT}
 * @param stepMillis how often a price is sent, 200 ms to 10 s; the exchange's mark price comes every second
 * @param spread the distance between the best bid and the best ask around the price, more than zero
 * @param points the path, its first point at second 0 and each later one further on, every price above zero
 */
public record ReplayScenario(
        String name,
        String description,
        MarketType market,
        String symbol,
        long stepMillis,
        BigDecimal spread,
        List<Point> points) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9]{2,30}");

    /**
     * One point of the path.
     *
     * @param at seconds after the start
     * @param price the price then
     */
    public record Point(long at, BigDecimal price) {}

    public ReplayScenario {
        Objects.requireNonNull(market, "market must not be null");
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a scenario name is 1 to 64 of a-z, 0-9 and _, not " + name);
        }
        if (symbol == null || !SYMBOL.matcher(symbol).matches()) {
            throw new IllegalArgumentException(name + ": a symbol is 2 to 30 of A-Z and 0-9, not " + symbol);
        }
        if (stepMillis < 200 || stepMillis > 10_000) {
            throw new IllegalArgumentException(name + ": stepMillis is 200 to 10000, not " + stepMillis);
        }
        if (spread == null || spread.signum() <= 0) {
            throw new IllegalArgumentException(name + ": the spread is above zero, not " + spread);
        }
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException(name + ": a scenario has at least one point");
        }
        points = List.copyOf(points);
        if (points.getFirst().at() != 0) {
            throw new IllegalArgumentException(name + ": the first point is at second 0");
        }
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            if (point.price() == null || point.price().signum() <= 0) {
                throw new IllegalArgumentException(name + ": every price is above zero, not " + point.price());
            }
            if (i > 0 && point.at() <= points.get(i - 1).at()) {
                throw new IllegalArgumentException(name + ": each point is later than the one before");
            }
        }
        description = description == null ? "" : description;
    }

    /** How long the path runs before its last price is held. */
    public Duration length() {
        return Duration.ofSeconds(points.getLast().at());
    }

    /** The step between two prices sent. */
    public Duration step() {
        return Duration.ofMillis(stepMillis);
    }

    /**
     * The price {@code elapsed} after the start: on the line between the two points around it, the first price before
     * the start and the last one after the end. Rounded to the scale of the nearest point's price.
     */
    public BigDecimal priceAt(Duration elapsed) {
        long millis = elapsed.toMillis();
        if (millis <= 0) {
            return points.getFirst().price();
        }
        for (int i = 1; i < points.size(); i++) {
            Point from = points.get(i - 1);
            Point to = points.get(i);
            long toMillis = to.at() * 1000;
            if (millis < toMillis) {
                long fromMillis = from.at() * 1000;
                BigDecimal share = BigDecimal.valueOf(millis - fromMillis)
                        .divide(BigDecimal.valueOf(toMillis - fromMillis), MathContext.DECIMAL64);
                BigDecimal price =
                        from.price().add(to.price().subtract(from.price()).multiply(share, MathContext.DECIMAL64));
                int scale = Math.max(from.price().scale(), to.price().scale());
                return price.setScale(Math.max(scale, 2), RoundingMode.HALF_EVEN);
            }
        }
        return points.getLast().price();
    }
}
