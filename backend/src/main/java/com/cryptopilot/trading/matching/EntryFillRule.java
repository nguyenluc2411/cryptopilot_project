package com.cryptopilot.trading.matching;

import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * When an entry is filled and at what price.
 *
 * <ul>
 *   <li>A LIMIT LONG entry is reached at or below its price, a LIMIT SHORT entry at or above it; both bounds count.
 *       Reached by a candle, it fills at the entry price, also when the candle traded through it (a gap).
 *   <li>At activation, a MARKET entry, or a LIMIT entry already reached by the last price, fills at once at the
 *       last price.
 *   <li>A candle that opened before the plan's activation never fills it (D-77): its high and low may hold prices
 *       traded before the plan existed.
 *   <li>A fill from a candle is recorded at the candle's open time (D-78), so the live updates of a minute and the
 *       same minute replayed after a restart give the same fill.
 * </ul>
 *
 * <p>Rule: BR-33; NSF-07; TECHNICAL_DESIGN 7.7; D-77, D-78.
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 (a buy limit executes at or below its price, a sell limit at or above it).
 * <p>Reference: Akidau, T. et al. (2015). The Dataflow Model. <i>PVLDB</i>, 8(12), 1792–1803 (results keyed by event
 * time, not by the time an update is processed).
 */
public final class EntryFillRule {

    private EntryFillRule() {}

    /**
     * Whether a price reaches a LIMIT entry: at or below it for LONG, at or above it for SHORT.
     *
     * <p>Rule: BR-33.
     */
    public static boolean reaches(Direction direction, BigDecimal price, BigDecimal entryPrice) {
        int compared = price.compareTo(entryPrice);
        return direction == Direction.LONG ? compared <= 0 : compared >= 0;
    }

    /**
     * Whether a range may fill an entry: its candle did not open before the plan was activated.
     *
     * <p>Rule: BR-33; D-77.
     */
    public static boolean mayFill(TrackedEntry entry, PriceRange range) {
        return !range.from().isBefore(entry.activatedAt());
    }

    /**
     * The fill of a LIMIT entry the range reached: the entry price, at the open time of the range's candle.
     *
     * <p>Rule: BR-33; D-78.
     */
    public static Fill byRange(TrackedEntry entry, PriceRange range) {
        return new Fill(entry.planId(), entry.entryPrice(), range.from());
    }

    /**
     * The fill price of an entry at its activation, or empty when it waits for the price.
     *
     * <p>Rule: BR-33.
     *
     * @param entryType MARKET fills at once; LIMIT only when the last price already reaches it
     * @param direction LONG or SHORT
     * @param entryPrice the LIMIT price; ignored for MARKET
     * @param lastPrice the current last price; empty when none is known, and then a LIMIT entry waits
     * @return the last price when the entry fills now
     * @throws IllegalArgumentException for a MARKET entry without a last price
     */
    public static Optional<BigDecimal> atActivation(
            EntryType entryType, Direction direction, BigDecimal entryPrice, Optional<BigDecimal> lastPrice) {
        Objects.requireNonNull(entryType, "entryType");
        if (entryType == EntryType.MARKET) {
            return Optional.of(lastPrice.orElseThrow(
                    () -> new IllegalArgumentException("a MARKET entry needs the current last price")));
        }
        return lastPrice.filter(price -> reaches(direction, price, entryPrice));
    }
}
