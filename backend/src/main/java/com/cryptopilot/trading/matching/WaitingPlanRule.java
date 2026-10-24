package com.cryptopilot.trading.matching;

import com.cryptopilot.trading.matching.PriceLevelBook.Side;
import com.cryptopilot.trading.model.Invalidation;
import com.cryptopilot.trading.model.PlanOutcome;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.InvalidationReason;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What one price range does to a waiting plan: fill its entry, invalidate it, or nothing.
 *
 * <p>A waiting plan has up to two invalidation levels, both on the book side opposite its entry, so the price can
 * reach them before the entry: a LIMIT plan's take profit (the move it waited for happened without it), a STOP plan's
 * stop loss (the breakout failed before it triggered), and the plan's own invalidation price.
 *
 * <ul>
 *   <li>Only an invalidation level reached: the plan is invalidated, with the reason of the nearest level reached.
 *   <li>Only the entry reached: it fills by {@link EntryFillRule#byRange}.
 *   <li>Both in one candle: a 1-minute candle hides the order of its prices, so its open decides when it already lies
 *       at or past one of them (an invalidation level: invalidated; the entry: filled). Otherwise, or when the open is
 *       unknown, invalidation wins, the pessimistic choice also made for a stop loss before a take profit (BR-35). An
 *       unknown open is logged with the plan, the pair and the candle's open time. The open cannot lie past both,
 *       because the entry and the invalidation levels were on opposite sides of the price at activation.
 *   <li>A candle that opened before the activation does neither (D-77).
 *   <li>The outcome is recorded at the candle's open time (D-78), so live updates and the replayed closed candle give
 *       the same outcome.
 * </ul>
 *
 * <p>Rule: BR-33, BR-35, BR-68; NSF-07; TECHNICAL_DESIGN 7.7; D-77, D-78; D-111 (M1, M3, E6); D-112 (R3, R4).
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 (limit and stop orders).
 * <p>Reference: Binance FAQ, <i>Types of Order on Binance Futures</i> (a stop-market order triggers on the last price).
 */
public final class WaitingPlanRule {

    private static final Logger log = LoggerFactory.getLogger(WaitingPlanRule.class);

    /** A price that invalidates a waiting plan, and the reason it gives. */
    public record Level(BigDecimal price, InvalidationReason reason) {

        public Level {
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private WaitingPlanRule() {}

    /**
     * The book side a plan's invalidation levels wait on: the side opposite its entry.
     *
     * <p>Rule: BR-68; TECHNICAL_DESIGN 7.7.
     */
    public static Side invalidationSide(TrackedEntry entry) {
        return EntryFillRule.entrySide(entry.entryType(), entry.direction()).opposite();
    }

    /**
     * The plan's invalidation levels, nearest to the price first: a LIMIT plan's take profit or a STOP plan's stop
     * loss, and the plan's own invalidation price when it has one. On a tie the take profit or stop loss comes first.
     *
     * <p>Rule: BR-68; D-111 (M3).
     */
    public static List<Level> invalidationLevels(TrackedEntry entry) {
        List<Level> levels = new ArrayList<>(2);
        levels.add(
                entry.entryType() == EntryType.LIMIT
                        ? new Level(entry.takeProfit(), InvalidationReason.TAKE_PROFIT_REACHED)
                        : new Level(entry.stopLoss(), InvalidationReason.STOP_LOSS_REACHED));
        if (entry.invalidationPrice() != null) {
            levels.add(new Level(entry.invalidationPrice(), InvalidationReason.INVALIDATION_PRICE));
        }
        // The price reaches an ABOVE level from below, so the lowest first; a BELOW level from above, the highest.
        Comparator<Level> nearest = Comparator.comparing(Level::price);
        levels.sort(invalidationSide(entry) == Side.ABOVE ? nearest : nearest.reversed());
        return levels;
    }

    /**
     * What the range does to the plan, or empty when it does nothing.
     *
     * <p>Rule: BR-33, BR-68; D-77, D-78; D-111 (M3, E6); D-112 (R3, R4).
     */
    public static Optional<PlanOutcome> decide(TrackedEntry entry, PriceRange range) {
        if (!EntryFillRule.mayAct(entry, range)) {
            return Optional.empty();
        }
        Side side = invalidationSide(entry);
        Optional<Level> reached = invalidationLevels(entry).stream()
                .filter(level -> side.reachedBy(range.low(), range.high(), level.price()))
                .findFirst();
        boolean entered = EntryFillRule.entryReached(entry, range);
        if (reached.isEmpty()) {
            return entered ? Optional.of(EntryFillRule.byRange(entry, range)) : Optional.empty();
        }
        Invalidation invalidation = new Invalidation(entry, reached.get().reason(), range.from());
        if (!entered) {
            return Optional.of(invalidation);
        }
        BigDecimal open = range.open();
        if (open == null) {
            log.info(
                    "D-112 R4 plan {} on {} {}: the candle opened at {} reached its entry and its {} with an unknown"
                            + " open; invalidation wins",
                    entry.planId(),
                    entry.market(),
                    entry.pairId(),
                    range.from(),
                    reached.get().reason());
            return Optional.of(invalidation);
        }
        boolean openedPastEntry =
                EntryFillRule.entrySide(entry.entryType(), entry.direction()).reachedAt(open, entry.entryPrice());
        if (openedPastEntry && !side.reachedAt(open, reached.get().price())) {
            return Optional.of(EntryFillRule.byRange(entry, range));
        }
        return Optional.of(invalidation);
    }
}
