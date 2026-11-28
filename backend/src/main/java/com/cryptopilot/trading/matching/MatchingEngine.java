package com.cryptopilot.trading.matching;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.matching.PriceLevelBook.Side;
import com.cryptopilot.trading.matching.WaitingPlanRule.Level;
import com.cryptopilot.trading.model.PlanOutcome;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.PriceSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The books of one partition, for each (market, pair), each with a BELOW book of levels that fire when the last price
 * falls to them and an ABOVE book of levels that fire when it rises to them (TECHNICAL_DESIGN 7.7). A waiting plan has
 * its invalidation levels there; each plan a range reached is decided by {@link WaitingPlanRule} and, once decided,
 * leaves the books. Whether an invalidation is stored is decided by a compare-and-set on the plan's status, not here.
 *
 * <p>The entry, its take profit, stop loss and liquidation are the demo account's paper orders since RF-33 (D-117, one
 * engine): this engine only ends a plan whose setup no longer holds before its entry fills (BR-68).
 *
 * <p>Not thread-safe on purpose: one consumer thread owns an engine, so its books need no locks and see the updates
 * of a pair in arrival order.
 *
 * <p>Rule: NSF-07, BR-68; TECHNICAL_DESIGN 7.7; D-77; D-111 (M3); D-117; RF-33; ADR-011.
 *
 * <p>Reference: Hewitt, C., Bishop, P. &amp; Steiger, R. (1973). A universal modular ACTOR formalism for artificial
 * intelligence. <i>IJCAI</i>, 235–245 (state owned by a single consumer of a message queue).
 */
public final class MatchingEngine {

    private final Map<BookKey, Books> books = new HashMap<>();
    private final Map<UUID, TrackedEntry> tracked = new HashMap<>();

    /** Starts tracking a plan's invalidation levels, replacing any earlier entry of the same plan. */
    public void track(TrackedEntry entry) {
        untrack(entry.planId());
        place(books.computeIfAbsent(new BookKey(entry.market(), entry.pairId()), ignored -> new Books()), entry);
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
        remove(pair, entry);
        if (pair.isEmpty()) {
            books.remove(key);
        }
    }

    /**
     * The invalidations a last price range decided; the plans decided leave the books. A plan activated after the
     * range's candle opened stays (D-77). A mark price range decides nothing here.
     */
    public List<PlanOutcome> onRange(PriceRange range) {
        if (range.source() == PriceSource.MARK) {
            return List.of();
        }
        BookKey key = new BookKey(range.market(), range.pairId());
        Books pair = books.get(key);
        if (pair == null) {
            return List.of();
        }
        // A plan may have fired several levels; it is decided once.
        Set<UUID> ids = new LinkedHashSet<>(pair.below.fire(range.low(), range.high()));
        ids.addAll(pair.above.fire(range.low(), range.high()));
        List<PlanOutcome> outcomes = new ArrayList<>();
        for (UUID id : ids) {
            TrackedEntry entry = tracked.get(id);
            Optional<PlanOutcome> outcome = WaitingPlanRule.decide(entry, range);
            if (outcome.isPresent()) {
                tracked.remove(id);
                remove(pair, entry);
                outcomes.add(outcome.get());
            } else {
                // Held back by D-77: its fired levels go back.
                place(pair, entry);
            }
        }
        if (pair.isEmpty()) {
            books.remove(key);
        }
        return outcomes;
    }

    /** How many plans are tracked. */
    public int size() {
        return tracked.size();
    }

    private static void place(Books pair, TrackedEntry entry) {
        PriceLevelBook opposite = pair.of(WaitingPlanRule.invalidationSide(entry));
        for (Level level : WaitingPlanRule.invalidationLevels(entry)) {
            opposite.add(level.price(), entry.planId());
        }
    }

    private static void remove(Books pair, TrackedEntry entry) {
        PriceLevelBook opposite = pair.of(WaitingPlanRule.invalidationSide(entry));
        for (Level level : WaitingPlanRule.invalidationLevels(entry)) {
            opposite.remove(level.price(), entry.planId());
        }
    }

    private record BookKey(MarketType market, UUID pairId) {}

    private static final class Books {

        private final PriceLevelBook below = new PriceLevelBook(Side.BELOW);
        private final PriceLevelBook above = new PriceLevelBook(Side.ABOVE);

        PriceLevelBook of(Side side) {
            return side == Side.BELOW ? below : above;
        }

        boolean isEmpty() {
            return below.isEmpty() && above.isEmpty();
        }
    }
}
