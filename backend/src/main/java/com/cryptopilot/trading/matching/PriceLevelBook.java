package com.cryptopilot.trading.matching;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Plan ids waiting at price levels, on one side of the price: a {@link Side#BELOW} book holds levels that fire when
 * the price trades at or below them, an {@link Side#ABOVE} book levels that fire at or above them. Not thread-safe:
 * one consumer owns it.
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 7.7.
 *
 * <p>Reference: Cormen, T. H., Leiserson, C. E., Rivest, R. L. &amp; Stein, C. (2009). <i>Introduction to
 * Algorithms</i> (3rd ed.). MIT Press, ch. 13 (red-black trees: O(log n) insert, delete and range search).
 */
public final class PriceLevelBook {

    /** Which way the price must move for a level to fire. */
    public enum Side {
        /** Fires when the price trades at or below the level. */
        BELOW,
        /** Fires when the price trades at or above the level. */
        ABOVE
    }

    private final Side side;
    private final TreeMap<BigDecimal, Set<UUID>> levels = new TreeMap<>();

    public PriceLevelBook(Side side) {
        this.side = Objects.requireNonNull(side, "side");
    }

    public void add(BigDecimal level, UUID id) {
        levels.computeIfAbsent(level, ignored -> new HashSet<>()).add(id);
    }

    public void remove(BigDecimal level, UUID id) {
        Set<UUID> ids = levels.get(level);
        if (ids != null && ids.remove(id) && ids.isEmpty()) {
            levels.remove(level);
        }
    }

    /**
     * Removes and returns the ids whose level the range {@code [low, high]} reached: levels at or above {@code low}
     * on a BELOW book, at or below {@code high} on an ABOVE book. Both bounds are inclusive.
     */
    public List<UUID> fire(BigDecimal low, BigDecimal high) {
        NavigableMap<BigDecimal, Set<UUID>> reached =
                side == Side.BELOW ? levels.tailMap(low, true) : levels.headMap(high, true);
        List<UUID> fired = new ArrayList<>();
        reached.values().forEach(fired::addAll);
        reached.clear();
        return fired;
    }

    public boolean isEmpty() {
        return levels.isEmpty();
    }
}
