package com.cryptopilot.trading.matching;

import com.cryptopilot.trading.matching.PriceLevelBook.Side;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * The sides and timing of a plan's entry, which the paper orders now fill (RF-33, D-117).
 *
 * <ul>
 *   <li>A LIMIT LONG entry is reached at or below its price, a LIMIT SHORT entry at or above it; both bounds count.
 *   <li>A STOP LONG entry triggers at or above its stop price, a STOP SHORT entry at or below it.
 *   <li>A LIMIT the last price already reaches takes liquidity, as MARKET and STOP do; otherwise it rests (BR-36).
 *   <li>A candle that opened before the plan's activation never invalidates it (D-77, D-112 R3): its high and low may
 *       hold prices traded before the plan existed.
 * </ul>
 *
 * <p>Rule: BR-33, BR-36; NSF-07; TECHNICAL_DESIGN 7.7; D-77; D-111 (E1); D-112 (R3); D-117.
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
     * The liquidity role an entry is expected to fill with: MARKET and STOP take liquidity; a LIMIT takes it when the
     * last price already reaches it, so it fills at activation, and otherwise rests and is reached later as maker.
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
}
