package com.cryptopilot.trading.matching;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.PositionLevelChange;
import com.cryptopilot.trading.matching.PriceLevelBook.Side;
import com.cryptopilot.trading.matching.WaitingPlanRule.Level;
import com.cryptopilot.trading.model.Excursion;
import com.cryptopilot.trading.model.Exit;
import com.cryptopilot.trading.model.OpenPosition;
import com.cryptopilot.trading.model.PlanOutcome;
import com.cryptopilot.trading.model.PositionExit;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.PriceSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The books of one partition, for each (market, pair), each with a BELOW book of levels that fire when the price falls
 * to them and an ABOVE book of levels that fire when it rises to them (TECHNICAL_DESIGN 7.7):
 *
 * <ul>
 *   <li>entry books, read by last prices: a waiting plan has its entry on one side (LIMIT LONG and STOP SHORT below,
 *       LIMIT SHORT and STOP LONG above) and its invalidation levels on the other; each plan a range reached is decided
 *       by {@link WaitingPlanRule} and, once decided, leaves both;
 *   <li>exit books, read by last prices: an open position's stop loss and take profit (a LONG stop and a SHORT take
 *       profit below, a LONG take profit and a SHORT stop above), decided by {@link ExitRule#byRange};
 *   <li>mark books, read by Futures mark prices: an open position's liquidation price (LONG below, SHORT above),
 *       decided by {@link ExitRule#byMark}.
 * </ul>
 *
 * The books of plans and of positions are kept apart, so an id fired by a book always names what that book holds.
 * Whether an outcome is stored is decided by a compare-and-set, on the plan's status or the journal row's, not here.
 * Each last price range also widens the excursions of every open position of its pair (D-112 R5).
 *
 * <p>A change of a position's levels (TP-E) acts from its instant: a range whose candle opened before the change is
 * decided by the levels in force when that candle opened, kept in the position's history, as a plan activated in the
 * middle of a minute is not filled by that minute (D-77). So a live update and the same candle replayed after a crash
 * give the same exit, whenever the change was made.
 *
 * <p>Not thread-safe on purpose: one consumer thread owns an engine, so its books need no locks and see the updates
 * of a pair in arrival order.
 *
 * <p>Only LIMIT and STOP entries wait here: a MARKET entry, or a LIMIT the last price already reached, is filled at
 * activation, and its position is opened here after the commit.
 *
 * <p>Rule: NSF-07, BR-33, BR-35, BR-68; TECHNICAL_DESIGN 7.7; D-09, D-77, D-78; D-111 (M1, M3); D-112 R5; D-124;
 * ADR-011.
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 (a buy limit executes at or below its price, a sell limit at or above it).
 * <p>Reference: Hewitt, C., Bishop, P. &amp; Steiger, R. (1973). A universal modular ACTOR formalism for artificial
 * intelligence. <i>IJCAI</i>, 235–245 (state owned by a single consumer of a message queue).
 */
public final class MatchingEngine {

    private final Map<BookKey, Books> books = new HashMap<>();
    private final Map<UUID, TrackedEntry> tracked = new HashMap<>();

    private final Map<BookKey, Books> exitBooks = new HashMap<>();
    private final Map<BookKey, Books> markBooks = new HashMap<>();

    /** The open positions by journal id. */
    private final Map<UUID, OpenState> positions = new HashMap<>();

    /** The open positions of each pair, in the order they were opened, whose excursions each range widens. */
    private final Map<BookKey, Set<UUID>> positionsByPair = new HashMap<>();

    /** The level changes of each open position that has any, oldest first (TP-E). */
    private final Map<UUID, List<PositionLevelChange>> levelChanges = new HashMap<>();

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
     * Starts tracking an open position's stop loss, take profit and liquidation price. A position already tracked has
     * its levels replaced and keeps its excursions, as after a change of its levels (TP-E); a new one starts its
     * excursions at the entry price.
     */
    public void open(TrackedPosition position) {
        OpenState earlier = positions.get(position.positionId());
        Excursion excursion = earlier == null ? Excursion.at(position.entryPrice()) : earlier.excursion;
        drop(position.positionId());
        OpenState state = new OpenState(position, excursion);
        positions.put(position.positionId(), state);
        positionsByPair
                .computeIfAbsent(new BookKey(position.market(), position.pairId()), ignored -> new LinkedHashSet<>())
                .add(position.positionId());
        placeExits(state);
    }

    /**
     * Opens a position that a fill of {@code range} has just stored and applies that same range to it: a STOP filled
     * at the candle's open, or a LIMIT filled at activation beyond its stop loss, can exit in the candle it entered in.
     *
     * @return the exit the range decided, if any; the position then leaves the books
     */
    public List<PlanOutcome> openAfterFill(TrackedPosition position, PriceRange range) {
        open(position);
        return exits(range, Set.of(position.positionId()));
    }

    /**
     * Replaces a tracked position's levels with the ones a change committed, at the row's new version, and keeps the
     * levels it replaces for the ranges of candles that opened before the change.
     *
     * <p>Rule: BR-35; D-77, D-124.
     */
    public void changeLevels(TrackedPosition position, PositionLevelChange change) {
        recordLevelChange(change);
        open(position);
    }

    /**
     * Adds a change to the history of a position, keeping it oldest first, without touching its current levels: the
     * engine rebuilt from the journal at start-up knows the current levels already.
     *
     * <p>Rule: D-124.
     */
    public void recordLevelChange(PositionLevelChange change) {
        List<PositionLevelChange> history =
                levelChanges.computeIfAbsent(change.positionId(), ignored -> new ArrayList<>());
        int at = history.size();
        while (at > 0 && history.get(at - 1).changedAt().isAfter(change.changedAt())) {
            at--;
        }
        history.add(at, change);
    }

    /** Stops tracking an open position and forgets its level changes; unknown positions are ignored. */
    public void close(UUID positionId) {
        drop(positionId);
        levelChanges.remove(positionId);
    }

    private void drop(UUID positionId) {
        OpenState state = positions.get(positionId);
        if (state != null) {
            forget(state);
            BookKey key = new BookKey(state.position.market(), state.position.pairId());
            dropIfEmpty(exitBooks, key);
            dropIfEmpty(markBooks, key);
        }
    }

    /**
     * The outcomes the range decided. A last price range: the fills of the entries it reached, the invalidations of
     * the plans whose invalidation level it reached first, and the stop losses and take profits it reached; a mark
     * price range: the liquidations it reached. Plans and positions decided leave the books. A plan activated after
     * the range's candle opened stays (D-77), and so does a position the range cannot act on (Q-59).
     */
    public List<PlanOutcome> onRange(PriceRange range) {
        if (range.source() == PriceSource.MARK) {
            return liquidations(range);
        }
        List<PlanOutcome> outcomes = new ArrayList<>(entries(range));
        outcomes.addAll(exits(range, null));
        return outcomes;
    }

    /** How many plans are tracked. */
    public int size() {
        return tracked.size();
    }

    /** How many open positions are tracked. */
    public int positionCount() {
        return positions.size();
    }

    /** The excursions of a tracked open position, or empty when it is not tracked. */
    public Optional<Excursion> excursion(UUID positionId) {
        return Optional.ofNullable(positions.get(positionId)).map(state -> state.excursion);
    }

    private List<PlanOutcome> entries(PriceRange range) {
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

    /**
     * The stop losses and take profits the range reached, of every position of its pair or of {@code only}; the
     * excursions of those positions widen by the range, clamped at the exit price for a position that exits.
     */
    private List<PlanOutcome> exits(PriceRange range, Set<UUID> only) {
        BookKey key = new BookKey(range.market(), range.pairId());
        if (!positionsByPair.containsKey(key)) {
            return List.of();
        }
        Books pair = exitBooks.computeIfAbsent(key, ignored -> new Books());
        // A position changed after the range's candle opened is decided by its earlier levels, outside the books.
        List<OpenState> changedSince = new ArrayList<>();
        for (UUID id : positionsByPair.get(key)) {
            if ((only == null || only.contains(id)) && changedAfter(id, range.from())) {
                OpenState state = positions.get(id);
                removeLevels(pair, state);
                changedSince.add(state);
            }
        }
        Set<UUID> fired = new LinkedHashSet<>(pair.below.fire(range.low(), range.high()));
        fired.addAll(pair.above.fire(range.low(), range.high()));
        List<PlanOutcome> outcomes = new ArrayList<>();
        for (UUID id : fired) {
            OpenState state = positions.get(id);
            if (only != null && !only.contains(id)) {
                placeLevels(pair, state);
                continue;
            }
            decide(pair, state, state.position.levels(), range).ifPresent(outcomes::add);
        }
        for (OpenState state : changedSince) {
            decide(pair, state, levelsAt(state, range.from()), range).ifPresent(outcomes::add);
        }
        widenExcursions(key, range, only);
        dropIfEmpty(exitBooks, key);
        return outcomes;
    }

    /**
     * The exit the range decides for one position by {@code levels}; the position then leaves the engine, with its
     * excursions clamped at the exit price. Otherwise its current levels go back into the books: the entry candle
     * cannot take profit, and a candle before the entry acts on nothing.
     */
    private Optional<PositionExit> decide(Books pair, OpenState state, OpenPosition levels, PriceRange range) {
        Optional<Exit> exit = ExitRule.byRange(levels, range);
        if (exit.isEmpty()) {
            placeLevels(pair, state);
            return Optional.empty();
        }
        Excursion excursion = state.excursion.withExit(
                state.position.direction(),
                range,
                ExitRule.isEntryCandle(levels, range),
                exit.get().reason(),
                exit.get().price());
        forget(state);
        levelChanges.remove(state.position.positionId());
        return Optional.of(new PositionExit(state.position, exit.get(), excursion));
    }

    /** Whether a change of the position's levels was made after the candle that opened at {@code candleOpen}. */
    private boolean changedAfter(UUID positionId, Instant candleOpen) {
        List<PositionLevelChange> history = levelChanges.get(positionId);
        return history != null && history.getLast().changedAt().isAfter(candleOpen);
    }

    /**
     * The levels in force when the candle opened at {@code candleOpen}: the current ones, unwound through every change
     * made after that instant. A change made exactly at the candle's open acts on it.
     *
     * <p>Rule: D-77, D-124.
     */
    private OpenPosition levelsAt(OpenState state, Instant candleOpen) {
        TrackedPosition position = state.position;
        BigDecimal stopLoss = position.stopLoss();
        BigDecimal takeProfit = position.takeProfit();
        List<PositionLevelChange> history = levelChanges.getOrDefault(position.positionId(), List.of());
        for (int i = history.size() - 1; i >= 0 && history.get(i).changedAt().isAfter(candleOpen); i--) {
            stopLoss = history.get(i).oldStopLoss();
            takeProfit = history.get(i).oldTakeProfit();
        }
        return new OpenPosition(
                position.planId(),
                position.direction(),
                position.entryPrice(),
                position.entryTime(),
                takeProfit,
                stopLoss,
                position.liquidationPrice());
    }

    /** The excursions of the pair's positions still open, or of {@code only}, widen by the last price range. */
    private void widenExcursions(BookKey key, PriceRange range, Set<UUID> only) {
        for (UUID id : positionsByPair.getOrDefault(key, Set.of())) {
            OpenState state = positions.get(id);
            OpenPosition levels = state.position.levels();
            if ((only == null || only.contains(id)) && ExitRule.actsOn(levels, range)) {
                state.excursion =
                        state.excursion.with(state.position.direction(), range, ExitRule.isEntryCandle(levels, range));
            }
        }
    }

    private List<PlanOutcome> liquidations(PriceRange marks) {
        BookKey key = new BookKey(marks.market(), marks.pairId());
        Books pair = markBooks.get(key);
        if (pair == null) {
            return List.of();
        }
        Set<UUID> fired = new LinkedHashSet<>(pair.below.fire(marks.low(), marks.high()));
        fired.addAll(pair.above.fire(marks.low(), marks.high()));
        List<PlanOutcome> outcomes = new ArrayList<>();
        for (UUID id : fired) {
            OpenState state = positions.get(id);
            Optional<Exit> exit = ExitRule.byMark(state.position.levels(), marks);
            if (exit.isPresent()) {
                forget(state);
                levelChanges.remove(id);
                outcomes.add(new PositionExit(state.position, exit.get(), state.excursion));
            } else {
                // A mark price candle that ended before the entry: back in.
                placeLiquidation(pair, state);
            }
        }
        dropIfEmpty(markBooks, key);
        return outcomes;
    }

    /** The position leaves every book and the engine. */
    private void forget(OpenState state) {
        TrackedPosition position = state.position;
        positions.remove(position.positionId());
        BookKey key = new BookKey(position.market(), position.pairId());
        Set<UUID> ofPair = positionsByPair.get(key);
        ofPair.remove(position.positionId());
        if (ofPair.isEmpty()) {
            positionsByPair.remove(key);
        }
        removeExits(state);
    }

    private void placeExits(OpenState state) {
        BookKey key = new BookKey(state.position.market(), state.position.pairId());
        placeLevels(exitBooks.computeIfAbsent(key, ignored -> new Books()), state);
        if (hasLiquidation(state.position)) {
            placeLiquidation(markBooks.computeIfAbsent(key, ignored -> new Books()), state);
        }
    }

    /** Takes the position's levels out of its books; empty books are dropped by the caller once it is done. */
    private void removeExits(OpenState state) {
        TrackedPosition position = state.position;
        BookKey key = new BookKey(position.market(), position.pairId());
        Books exits = exitBooks.get(key);
        if (exits != null) {
            boolean isLong = position.direction() == Direction.LONG;
            removeLevel(exits, isLong ? Side.BELOW : Side.ABOVE, position.stopLoss(), position.positionId());
            removeLevel(exits, isLong ? Side.ABOVE : Side.BELOW, position.takeProfit(), position.positionId());
        }
        Books marks = markBooks.get(key);
        if (marks != null && hasLiquidation(position)) {
            removeLevel(marks, liquidationSide(position), position.liquidationPrice(), position.positionId());
        }
    }

    private static void dropIfEmpty(Map<BookKey, Books> byPair, BookKey key) {
        Books pair = byPair.get(key);
        if (pair != null && pair.isEmpty()) {
            byPair.remove(key);
        }
    }

    // A LONG stop and a SHORT take profit fire below the price, a LONG take profit and a SHORT stop above it.
    private static void placeLevels(Books pair, OpenState state) {
        TrackedPosition position = state.position;
        boolean isLong = position.direction() == Direction.LONG;
        addLevel(pair, isLong ? Side.BELOW : Side.ABOVE, position.stopLoss(), position.positionId());
        addLevel(pair, isLong ? Side.ABOVE : Side.BELOW, position.takeProfit(), position.positionId());
    }

    private static void removeLevels(Books pair, OpenState state) {
        TrackedPosition position = state.position;
        boolean isLong = position.direction() == Direction.LONG;
        removeLevel(pair, isLong ? Side.BELOW : Side.ABOVE, position.stopLoss(), position.positionId());
        removeLevel(pair, isLong ? Side.ABOVE : Side.BELOW, position.takeProfit(), position.positionId());
    }

    private static void placeLiquidation(Books pair, OpenState state) {
        addLevel(pair, liquidationSide(state.position), state.position.liquidationPrice(), state.position.positionId());
    }

    private static Side liquidationSide(TrackedPosition position) {
        return position.direction() == Direction.LONG ? Side.BELOW : Side.ABOVE;
    }

    private static boolean hasLiquidation(TrackedPosition position) {
        BigDecimal liquidation = position.liquidationPrice();
        return liquidation != null && liquidation.signum() > 0;
    }

    private static void addLevel(Books pair, Side side, BigDecimal level, UUID id) {
        if (level != null) {
            pair.of(side).add(level, id);
        }
    }

    private static void removeLevel(Books pair, Side side, BigDecimal level, UUID id) {
        if (level != null) {
            pair.of(side).remove(level, id);
        }
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

    /** A tracked open position and its excursions so far. */
    private static final class OpenState {

        private final TrackedPosition position;
        private Excursion excursion;

        OpenState(TrackedPosition position, Excursion excursion) {
            this.position = position;
            this.excursion = excursion;
        }
    }

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
