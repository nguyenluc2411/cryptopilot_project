package com.cryptopilot.trading.matching;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.matching.PriceLevelBook.Side;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.Direction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The books of one partition: for each (market, pair), a BELOW book of LONG entries and an ABOVE book of SHORT
 * entries. A price range fires every entry it reached and drops it from the books; whether the fill then happens is
 * decided by the compare-and-set on the plan's status, not here.
 *
 * <p>Not thread-safe on purpose: one consumer thread owns an engine, so its books need no locks and see the updates
 * of a pair in arrival order.
 *
 * <p>Only LIMIT entries are tracked here. The fill price, marketable limits and MARKET entries are T-043; exits and
 * the mark price books are T-044.
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 7.7; D-09; ADR-011.
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 (a buy limit executes at or below its price, a sell limit at or above it).
 * <p>Reference: Hewitt, C., Bishop, P. &amp; Steiger, R. (1973). A universal modular ACTOR formalism for artificial
 * intelligence. <i>IJCAI</i>, 235–245 (state owned by a single consumer of a message queue).
 */
public final class MatchingEngine {

    private final Map<BookKey, Books> books = new HashMap<>();
    private final Map<UUID, TrackedEntry> tracked = new HashMap<>();

    /** Starts tracking an entry, replacing any earlier entry of the same plan. */
    public void track(TrackedEntry entry) {
        untrack(entry.planId());
        books.computeIfAbsent(new BookKey(entry.market(), entry.pairId()), ignored -> new Books())
                .of(entry.direction())
                .add(entry.entryPrice(), entry.planId());
        tracked.put(entry.planId(), entry);
    }

    /** Stops tracking a plan; unknown plans are ignored. */
    public void untrack(UUID planId) {
        TrackedEntry entry = tracked.remove(planId);
        if (entry == null) {
            return;
        }
        BookKey key = new BookKey(entry.market(), entry.pairId());
        Books pair = books.get(key);
        pair.of(entry.direction()).remove(entry.entryPrice(), planId);
        if (pair.isEmpty()) {
            books.remove(key);
        }
    }

    /**
     * The entries the range reached, removed from the books: LONG entries at or above its low, SHORT entries at or
     * below its high.
     */
    public List<TrackedEntry> onRange(PriceRange range) {
        BookKey key = new BookKey(range.market(), range.pairId());
        Books pair = books.get(key);
        if (pair == null) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(pair.below.fire(range.low(), range.high()));
        ids.addAll(pair.above.fire(range.low(), range.high()));
        if (pair.isEmpty()) {
            books.remove(key);
        }
        return ids.stream().map(tracked::remove).toList();
    }

    /** How many entries are tracked. */
    public int size() {
        return tracked.size();
    }

    private record BookKey(MarketType market, UUID pairId) {}

    private static final class Books {

        private final PriceLevelBook below = new PriceLevelBook(Side.BELOW);
        private final PriceLevelBook above = new PriceLevelBook(Side.ABOVE);

        PriceLevelBook of(Direction direction) {
            return direction == Direction.LONG ? below : above;
        }

        boolean isEmpty() {
            return below.isEmpty() && above.isEmpty();
        }
    }
}
