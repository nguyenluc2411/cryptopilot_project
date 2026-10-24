package com.cryptopilot.trading.matching;

import com.cryptopilot.trading.matching.PriceLevelBook.Side;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * When an entry is filled and at what price.
 *
 * <ul>
 *   <li>A LIMIT LONG entry is reached at or below its price, a LIMIT SHORT entry at or above it; both bounds count.
 *       Reached by a candle, it rested, so it fills as maker at the entry price, also when the candle traded through
 *       it (a gap, D-111 E7).
 *   <li>A STOP LONG entry triggers at or above its stop price, a STOP SHORT entry at or below it, on the last-price
 *       range. It is a stop-market order, so it fills as taker at the stop price, or at the candle's open when the
 *       candle opened past the stop (a gap); the slippage moves it when it is stored, and nothing caps it.
 *   <li>At activation, a MARKET entry, or a LIMIT entry already reached by the last price, fills at once at the
 *       last price.
 *   <li>A candle that opened before the plan's activation never fills or invalidates it (D-77, D-112 R3): its high
 *       and low may hold prices traded before the plan existed.
 *   <li>A fill from a candle is recorded at the candle's open time (D-78), so the live updates of a minute and the
 *       same minute replayed after a restart give the same fill.
 * </ul>
 *
 * <p>Rule: BR-33, BR-36; NSF-07; TECHNICAL_DESIGN 7.7; D-77, D-78; D-111 (M1, E1, E7); D-112 (R3, R6).
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 (a buy limit executes at or below its price, a sell limit at or above it; a stop order
 * becomes a market order once the price trades at or through its stop).
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
     * Whether a range may act on a waiting plan, by filling or by invalidating it: its candle did not open before the
     * plan was activated.
     *
     * <p>Rule: BR-33, BR-68; D-77; D-112 (R3).
     */
    public static boolean mayAct(TrackedEntry entry, PriceRange range) {
        return !range.from().isBefore(entry.activatedAt());
    }

    /**
     * The book side an entry waits on: a LIMIT LONG and a STOP SHORT fire as the price falls to them (BELOW), a LIMIT
     * SHORT and a STOP LONG as it rises to them (ABOVE).
     *
     * <p>Rule: BR-33; TECHNICAL_DESIGN 7.7; D-111 (M1).
     */
    public static Side entrySide(EntryType entryType, Direction direction) {
        Objects.requireNonNull(direction, "direction");
        boolean isLong = direction == Direction.LONG;
        return switch (Objects.requireNonNull(entryType, "entryType")) {
            case LIMIT -> isLong ? Side.BELOW : Side.ABOVE;
            case STOP -> isLong ? Side.ABOVE : Side.BELOW;
            case MARKET -> throw new IllegalArgumentException("a MARKET entry never waits in the books");
        };
    }

    /**
     * Whether the range reached the entry: a LIMIT at or through its price, a STOP at or through its stop price.
     *
     * <p>Rule: BR-33; D-111 (M1).
     */
    public static boolean entryReached(TrackedEntry entry, PriceRange range) {
        return entrySide(entry.entryType(), entry.direction()).reachedBy(range.low(), range.high(), entry.entryPrice());
    }

    /**
     * The fill of an entry the range reached, at the open time of the range's candle. A LIMIT rested: maker, at its
     * own price, also through a gap. A STOP took liquidity: taker, at the stop price, or at the candle's open when the
     * candle opened past it; this is the price before slippage.
     *
     * <p>Rule: BR-33, BR-36; D-78; D-111 (M1, E1, E7); D-112 (R6).
     */
    public static Fill byRange(TrackedEntry entry, PriceRange range) {
        if (entry.entryType() == EntryType.LIMIT) {
            return new Fill(entry, LiquidityRole.MAKER, entry.entryPrice(), range.from());
        }
        Side side = entrySide(entry.entryType(), entry.direction());
        BigDecimal open = range.open();
        BigDecimal price = open != null && side.reachedAt(open, entry.entryPrice()) ? open : entry.entryPrice();
        return new Fill(entry, LiquidityRole.TAKER, price, range.from());
    }

    /**
     * The liquidity role an entry is expected to fill with: MARKET and STOP take liquidity; a LIMIT takes it when the
     * last price already reaches it, so it fills at activation, and otherwise rests and is reached later as maker.
     * The same test as {@link #atActivation}, so the estimate and the fill agree.
     *
     * <p>Rule: BR-33, BR-36; D-111 E1.
     *
     * @param lastPrice the current last price; empty when none is known, and then a LIMIT is expected to rest
     */
    public static LiquidityRole expectedLiquidity(
            EntryType entryType, Direction direction, BigDecimal entryPrice, Optional<BigDecimal> lastPrice) {
        return switch (Objects.requireNonNull(entryType, "entryType")) {
            case MARKET, STOP -> LiquidityRole.TAKER;
            case LIMIT ->
                lastPrice.filter(price -> reaches(direction, price, entryPrice)).isPresent()
                        ? LiquidityRole.TAKER
                        : LiquidityRole.MAKER;
        };
    }

    /**
     * The fill price of an entry at its activation, or empty when it waits for the price.
     *
     * <p>Rule: BR-33.
     *
     * @param entryType MARKET fills at once; LIMIT only when the last price already reaches it; STOP never does: one
     *     already triggered is refused before this is asked (D-112), one not triggered waits for the books
     * @param direction LONG or SHORT
     * @param entryPrice the LIMIT price; ignored for MARKET
     * @param lastPrice the current last price; empty when none is known, and then a LIMIT entry waits
     * @return the last price when the entry fills now
     * @throws IllegalArgumentException for a MARKET entry without a last price, or for a STOP entry
     */
    public static Optional<BigDecimal> atActivation(
            EntryType entryType, Direction direction, BigDecimal entryPrice, Optional<BigDecimal> lastPrice) {
        Objects.requireNonNull(entryType, "entryType");
        if (entryType == EntryType.STOP) {
            throw new IllegalArgumentException("a STOP entry is never filled at activation; it waits for its trigger");
        }
        if (entryType == EntryType.MARKET) {
            return Optional.of(lastPrice.orElseThrow(
                    () -> new IllegalArgumentException("a MARKET entry needs the current last price")));
        }
        return lastPrice.filter(price -> reaches(direction, price, entryPrice));
    }
}
