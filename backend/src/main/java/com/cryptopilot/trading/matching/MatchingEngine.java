package com.cryptopilot.trading.matching;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.matching.PriceLevelBook.Side;
import com.cryptopilot.trading.matching.WaitingPlanRule.Level;
import com.cryptopilot.trading.model.PlanOutcome;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The books of one partition: for each (market, pair), a BELOW book of levels that fire when the price falls to them
 * and an ABOVE book of levels that fire when it rises to them. A waiting plan has its entry on one side (LIMIT LONG and
 * STOP SHORT below, LIMIT SHORT and STOP LONG above) and its invalidation levels on the other (TECHNICAL_DESIGN 7.7).
 * A price range fires every level it reached; each plan concerned is decided by {@link WaitingPlanRule} and, once
 * decided, leaves both books. Whether the outcome is stored is decided by the compare-and-set on the plan's status,
 * not here.
 *
 * <p>Not thread-safe on purpose: one consumer thread owns an engine, so its books need no locks and see the updates
 * of a pair in arrival order.
 *
 * <p>Only LIMIT and STOP entries wait here: a MARKET entry, or a LIMIT the last price already reached, is filled at
 * activation. Exits and the mark price books are T-048.
 *
 * <p>Rule: NSF-07, BR-33, BR-68; TECHNICAL_DESIGN 7.7; D-09, D-77, D-78; D-111 (M1, M3); ADR-011.
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 (a buy limit executes at or below its price, a sell limit at or above it).
 * <p>Reference: Hewitt, C., Bishop, P. &amp; Steiger, R. (1973). A universal modular ACTOR formalism for artificial
 * intelligence. <i>IJCAI</i>, 235–245 (state owned by a single consumer of a message queue).
 */
public final class MatchingEngine {

    private final Map<BookKey, Books> books = new HashMap<>();
    private final Map<UUID, TrackedEntry> tracked = new HashMap<>();

    /** Starts tracking a plan's entry and invalidation levels, replacing any earlier entry of the same plan. */
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
     * The outcomes the range decided: the fills of the entries it reached and the invalidations of the plans whose
     * invalidation level it reached first; those plans leave the books. A plan activated after the range's candle
     * opened stays (D-77).
     */
    public List<PlanOutcome> onRange(PriceRange range) {
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
        pair.of(EntryFillRule.entrySide(entry.entryType(), entry.direction())).add(entry.entryPrice(), entry.planId());
        PriceLevelBook opposite = pair.of(WaitingPlanRule.invalidationSide(entry));
        for (Level level : WaitingPlanRule.invalidationLevels(entry)) {
            opposite.add(level.price(), entry.planId());
        }
    }

    private static void remove(Books pair, TrackedEntry entry) {
        pair.of(EntryFillRule.entrySide(entry.entryType(), entry.direction()))
                .remove(entry.entryPrice(), entry.planId());
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
