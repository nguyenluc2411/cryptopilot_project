package com.cryptopilot.paper.job;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.PriceRange;
import com.cryptopilot.paper.model.PriceRange.Print;
import com.cryptopilot.paper.model.enums.FillSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The market trades of one pair that arrived while the matching consumer was busy, gathered as they come and read
 * once as one {@link PriceRange} (TR-05, D-77). Appending a trade costs the same however many wait, so the stream
 * reader that appends never does work that grows with the backlog: the low, the high, each with the time of its
 * earliest trade, and the volume sold and bought at each price are kept as the trades come, and the range is built
 * once, when the consumer drains it.
 *
 * <p>Trades of one stream come in time order; one older than the last is put in its place by a scan from the back,
 * which is rare. At most {@code max} trades are kept one by one: past that the oldest is folded into the low, the high
 * and the volumes only, and the oldest trades at the low and the high stay as trades, so no price extreme is lost.
 *
 * <p>Not thread-safe: the stream thread appends under the map bin of its pair ({@code ConcurrentHashMap.compute}) and
 * the consumer drains it once removed from the map, after which nothing appends to it.
 *
 * <p>Rule: TR-05; Q-T8; D-77.
 *
 * <p>Reference: Goetz, B. et al. (2006). <i>Java Concurrency in Practice</i>. Addison-Wesley, ch. 5.3
 * (producer-consumer) and ch. 11.4 (reducing lock scope and hold time).
 */
final class PendingTrades {

    private final MarketType market;
    private final UUID pairId;
    private final int max;

    /** The trades kept one by one, earliest first. */
    private final Deque<Print> prints = new ArrayDeque<>();

    private final NavigableMap<BigDecimal, BigDecimal> sold = new TreeMap<>();
    private final NavigableMap<BigDecimal, BigDecimal> bought = new TreeMap<>();

    private Print low;
    private Print high;
    private Instant from;

    /** The trades folded out at the low and the high; {@code null} until one is folded. */
    private Print foldedLow;

    private Print foldedHigh;
    private long folded;

    PendingTrades(MarketType market, UUID pairId, int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be positive, was " + max);
        }
        this.market = market;
        this.pairId = pairId;
        this.max = max;
    }

    /**
     * Adds one trade.
     *
     * @return whether the oldest trade kept was folded to make room for it
     */
    boolean add(Print print) {
        if (prints.isEmpty() || !print.at().isBefore(prints.getLast().at())) {
            prints.addLast(print);
        } else {
            insertInOrder(print);
        }
        // Strictly lower or higher, or as low and earlier: the earliest trade at the low or the high keeps its time.
        if (low == null || isLower(print, low)) {
            low = print;
        }
        if (high == null || isHigher(print, high)) {
            high = print;
        }
        if (from == null || print.at().isBefore(from)) {
            from = print.at();
        }
        (print.takerSold() ? sold : bought).merge(print.price(), print.quantity(), BigDecimal::add);
        if (prints.size() <= max) {
            return false;
        }
        fold(prints.removeFirst());
        return true;
    }

    /** How many trades were folded out so far. */
    long folded() {
        return folded;
    }

    /** How many trades are kept one by one. */
    int size() {
        return prints.size();
    }

    /** The trades as one range; built once, by the consumer, after it removed this from the map. */
    PriceRange drain() {
        if (folded == 0) {
            return PriceRange.ofPrints(market, pairId, List.copyOf(prints));
        }
        List<Print> all = new ArrayList<>(prints.size() + 2);
        all.add(foldedLow);
        if (foldedHigh != foldedLow) {
            all.add(foldedHigh);
        }
        all.addAll(prints);
        // Nearly in order already (only the oldest are folded), so the stable sort is about linear.
        all.sort(Comparator.comparing(Print::at));
        return new PriceRange(
                market,
                pairId,
                low.price(),
                high.price(),
                from,
                false,
                FillSource.LIVE,
                low.at(),
                high.at(),
                sold,
                bought,
                null,
                all);
    }

    private void fold(Print oldest) {
        folded++;
        if (foldedLow == null || isLower(oldest, foldedLow)) {
            foldedLow = oldest;
        }
        if (foldedHigh == null || isHigher(oldest, foldedHigh)) {
            foldedHigh = oldest;
        }
    }

    /** A late trade, put after the last one kept that is not later than it; a linear scan from the back. */
    private void insertInOrder(Print print) {
        Deque<Print> later = new ArrayDeque<>();
        while (!prints.isEmpty() && print.at().isBefore(prints.getLast().at())) {
            later.addFirst(prints.removeLast());
        }
        prints.addLast(print);
        prints.addAll(later);
    }

    private static boolean isLower(Print print, Print than) {
        int compared = print.price().compareTo(than.price());
        return compared < 0 || (compared == 0 && print.at().isBefore(than.at()));
    }

    private static boolean isHigher(Print print, Print than) {
        int compared = print.price().compareTo(than.price());
        return compared > 0 || (compared == 0 && print.at().isBefore(than.at()));
    }
}
