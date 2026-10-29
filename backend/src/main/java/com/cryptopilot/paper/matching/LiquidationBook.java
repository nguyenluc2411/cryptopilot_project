package com.cryptopilot.paper.matching;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.matching.PaperOrderBook.BookKey;
import com.cryptopilot.paper.model.LiquidationLevel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The liquidation prices of the isolated Futures positions, per pair (TR-22, plan 9.2): a mark at or below a long's
 * level, or at or above a short's, reaches it. The engine replaces a Trader's levels on a pair whenever a committed
 * change of them is reported, so the book may lag a change by the time the report takes; every liquidation is decided
 * again under the Trader's lock.
 *
 * <p>Not thread-safe on purpose: one consumer thread owns a book.
 *
 * <p>Rule: TR-22; D-127.
 */
public final class LiquidationBook {

    private final Map<BookKey, Map<UUID, LiquidationLevel>> levels = new HashMap<>();

    /** Watches a level, replacing an earlier one of the same position. */
    public void track(LiquidationLevel level) {
        levels.computeIfAbsent(key(level.pairId()), ignored -> new LinkedHashMap<>())
                .put(level.positionId(), level);
    }

    /** Replaces every level of the Trader on the pair with these. */
    public void replace(UUID userId, UUID pairId, List<LiquidationLevel> current) {
        Map<UUID, LiquidationLevel> pair = levels.get(key(pairId));
        if (pair != null) {
            pair.values().removeIf(level -> level.userId().equals(userId));
        }
        current.forEach(this::track);
        Map<UUID, LiquidationLevel> after = levels.get(key(pairId));
        if (after != null && after.isEmpty()) {
            levels.remove(key(pairId));
        }
    }

    /** The levels of the pair the mark reached, the furthest from it first, so the worst loss goes first. */
    public List<LiquidationLevel> reached(UUID pairId, BigDecimal mark) {
        Map<UUID, LiquidationLevel> pair = levels.get(key(pairId));
        if (pair == null) {
            return List.of();
        }
        List<LiquidationLevel> reached = new ArrayList<>();
        for (LiquidationLevel level : pair.values()) {
            if (level.reachedBy(mark)) {
                reached.add(level);
            }
        }
        reached.sort(Comparator.comparing(level -> level.price().subtract(mark).abs(), Comparator.reverseOrder()));
        return reached;
    }

    /** The levels of the pair whose margin call price the mark reached, but not their liquidation price (TR-43). */
    public List<LiquidationLevel> marginCalls(UUID pairId, BigDecimal mark) {
        Map<UUID, LiquidationLevel> pair = levels.get(key(pairId));
        if (pair == null) {
            return List.of();
        }
        return pair.values().stream()
                .filter(level -> level.marginCallReachedBy(mark))
                .toList();
    }

    /** Whether any level of the pair is watched. */
    public boolean hasLevels(UUID pairId) {
        return levels.containsKey(key(pairId));
    }

    /** How many levels are watched. */
    public int size() {
        return levels.values().stream().mapToInt(Map::size).sum();
    }

    private static BookKey key(UUID pairId) {
        return new BookKey(MarketType.FUTURES, pairId);
    }
}
