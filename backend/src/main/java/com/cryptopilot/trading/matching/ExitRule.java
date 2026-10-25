package com.cryptopilot.trading.matching;

import com.cryptopilot.trading.model.Exit;
import com.cryptopilot.trading.model.OpenPosition;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/**
 * When an open position exits and at what price.
 *
 * <ul>
 *   <li>Take profit and stop loss by the last price range: a LONG stop at or below its level, a LONG take profit at
 *       or above it, and the mirror for SHORT. Both bounds count.
 *   <li>The take profit is a resting limit order: it exits at its own price, also when the candle opened beyond it.
 *       The stop loss is a stop-market order: it exits at its level, or at the candle's open when the candle opened
 *       beyond it (a gap); the slippage is applied when the exit is stored.
 *   <li>A candle whose open is already at or beyond one level is decided by that level. Otherwise, when one range
 *       reaches both, the stop loss is applied.
 *   <li>The candle in which the entry filled can only reach the stop loss, from the entry price on: the order of
 *       prices inside the minute is unknown, so only its adverse extreme counts, as for the excursions. A candle that
 *       ended before the entry never acts on the position.
 *   <li>Liquidation by the mark price only, always at the liquidation price.
 * </ul>
 *
 * <p>Rule: BR-35, BR-36; NSF-07; TECHNICAL_DESIGN 7.7; D-78, D-97, D-112 R5; Q-51, Q-53, Q-59, Q-61.
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 (a resting limit order trades at its own price; a stop order becomes a market order once
 * triggered).
 */
public final class ExitRule {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private ExitRule() {}

    /**
     * The take profit or stop loss the last price range reached, recorded at the candle's open time.
     *
     * <p>Rule: BR-35; Q-59, Q-61; D-112 R5.
     */
    public static Optional<Exit> byRange(OpenPosition position, PriceRange range) {
        if (!actsOn(position, range)) {
            return Optional.empty();
        }
        boolean isLong = position.direction() == Direction.LONG;
        BigDecimal stop = position.stopLoss();
        BigDecimal target = position.takeProfit();
        if (isEntryCandle(position, range)) {
            // From the fill on, only the adverse extreme is known to follow it.
            return stop != null && reachedStop(isLong, range, stop)
                    ? Optional.of(stopLoss(position, stop, position.entryPrice(), isLong, range))
                    : Optional.empty();
        }
        BigDecimal open = range.open();
        if (open != null) {
            if (stop != null && reachedAt(isLong ? -1 : 1, open, stop)) {
                return Optional.of(exit(position, CloseReason.STOP_LOSS, open, range));
            }
            if (target != null && reachedAt(isLong ? 1 : -1, open, target)) {
                return Optional.of(exit(position, CloseReason.TAKE_PROFIT, target, range));
            }
        }
        if (stop != null && reachedStop(isLong, range, stop)) {
            return Optional.of(exit(position, CloseReason.STOP_LOSS, stop, range));
        }
        if (target != null
                && (isLong ? range.high().compareTo(target) >= 0 : range.low().compareTo(target) <= 0)) {
            return Optional.of(exit(position, CloseReason.TAKE_PROFIT, target, range));
        }
        return Optional.empty();
    }

    /**
     * The liquidation the mark price range reached, at its adverse extreme: LONG at or below, SHORT at or above the
     * liquidation price. Always at the liquidation price, also through a gap; recorded at the candle's open time. A
     * mark price candle that ended before the entry never liquidates the position.
     *
     * <p>Rule: BR-27, BR-35; D-97; Q-54.
     */
    public static Optional<Exit> byMark(OpenPosition position, PriceRange marks) {
        BigDecimal liquidation = position.liquidationPrice();
        if (liquidation == null || liquidation.signum() <= 0 || !actsOn(position, marks)) {
            return Optional.empty();
        }
        boolean reached = position.direction() == Direction.LONG
                ? marks.low().compareTo(liquidation) <= 0
                : marks.high().compareTo(liquidation) >= 0;
        return reached
                ? Optional.of(new Exit(position.planId(), CloseReason.LIQUIDATION, liquidation, marks.from()))
                : Optional.empty();
    }

    /**
     * Whether the range belongs to the candle in which the entry filled: the candle opened at or before the entry time
     * and ended after it.
     *
     * <p>Rule: Q-59; D-78; D-112 R5.
     */
    public static boolean isEntryCandle(OpenPosition position, PriceRange range) {
        return !range.from().isAfter(position.entryTime()) && actsOn(position, range);
    }

    /**
     * Whether the range can act on the position at all: its candle ended after the entry. A candle that ended at or
     * before the entry time, which a replay can serve, never exits the position nor widens its excursions.
     *
     * <p>Rule: Q-59; D-77; D-78.
     */
    public static boolean actsOn(OpenPosition position, PriceRange range) {
        return range.from().plus(MINUTE).isAfter(position.entryTime());
    }

    /**
     * The liquidity role of an exit: a take profit rested and was then reached, so it is maker; a stop loss and a
     * manual close executed against the market, so they are taker. A liquidation has no role: it pays the clearance
     * fee instead.
     *
     * <p>Rule: BR-36; D-97; D-111 E1.
     *
     * @throws IllegalArgumentException for a liquidation
     */
    public static LiquidityRole liquidity(CloseReason reason) {
        return switch (reason) {
            case TAKE_PROFIT -> LiquidityRole.MAKER;
            case STOP_LOSS, MANUAL -> LiquidityRole.TAKER;
            case LIQUIDATION -> throw new IllegalArgumentException("a liquidation pays the clearance fee, not a rate");
        };
    }

    private static boolean reachedStop(boolean isLong, PriceRange range, BigDecimal stop) {
        return isLong ? range.low().compareTo(stop) <= 0 : range.high().compareTo(stop) >= 0;
    }

    // Whether one price is at or beyond the level in the direction it is reached (sign −1: below, +1: above).
    private static boolean reachedAt(int beyond, BigDecimal price, BigDecimal level) {
        return price.compareTo(level) * beyond >= 0;
    }

    // A stop loss at its level, or at the reference price when that is already at or beyond it.
    private static Exit stopLoss(
            OpenPosition position, BigDecimal stop, BigDecimal reference, boolean isLong, PriceRange range) {
        BigDecimal price = reachedAt(isLong ? -1 : 1, reference, stop) ? reference : stop;
        return exit(position, CloseReason.STOP_LOSS, price, range);
    }

    private static Exit exit(OpenPosition position, CloseReason reason, BigDecimal price, PriceRange range) {
        return new Exit(position.planId(), reason, price, range.from());
    }
}
