package com.cryptopilot.trading.matching;

import com.cryptopilot.trading.model.Exit;
import com.cryptopilot.trading.model.OpenPosition;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * When an open position exits and at what price.
 *
 * <ul>
 *   <li>Take profit and stop loss by the last price range: a LONG stop at or below its level, a LONG take profit at
 *       or above it, and the mirror for SHORT. Both bounds count; the exit is at the level, or at the candle's open
 *       when the candle opened beyond it (a gap).
 *   <li>When one range reaches both, the stop loss is applied.
 *   <li>Liquidation by the mark price only, always at the liquidation price.
 * </ul>
 *
 * <p>Rule: BR-35; NSF-07; TECHNICAL_DESIGN 7.7; D-78; Q-51, Q-53, Q-59.
 */
public final class ExitRule {

    private ExitRule() {}

    /**
     * The take profit or stop loss the range reached, stop loss first; recorded at the candle's open time.
     *
     * @param open the candle's first price, or {@code null} when unknown; a level the candle opened beyond fills there
     */
    public static Optional<Exit> byRange(OpenPosition position, PriceRange range, BigDecimal open) {
        boolean isLong = position.direction() == Direction.LONG;
        BigDecimal stop = position.stopLoss();
        if (stop != null
                && (isLong ? range.low().compareTo(stop) <= 0 : range.high().compareTo(stop) >= 0)) {
            return Optional.of(exit(position, CloseReason.STOP_LOSS, fill(stop, open, isLong ? -1 : 1), range));
        }
        BigDecimal target = position.takeProfit();
        if (target != null
                && (isLong ? range.high().compareTo(target) >= 0 : range.low().compareTo(target) <= 0)) {
            return Optional.of(exit(position, CloseReason.TAKE_PROFIT, fill(target, open, isLong ? 1 : -1), range));
        }
        return Optional.empty();
    }

    /** The liquidation the mark price reached: LONG at or below, SHORT at or above the liquidation price. */
    public static Optional<Exit> byMark(OpenPosition position, BigDecimal markPrice, Instant at) {
        BigDecimal liquidation = position.liquidationPrice();
        if (liquidation == null || liquidation.signum() <= 0) {
            return Optional.empty();
        }
        int compared = markPrice.compareTo(liquidation);
        boolean reached = position.direction() == Direction.LONG ? compared <= 0 : compared >= 0;
        return reached
                ? Optional.of(new Exit(position.planId(), CloseReason.LIQUIDATION, liquidation, at))
                : Optional.empty();
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

    // The open when it is beyond the level in the direction the level is reached (sign −1: below, +1: above).
    private static BigDecimal fill(BigDecimal level, BigDecimal open, int beyond) {
        return open != null && open.compareTo(level) * beyond > 0 ? open : level;
    }

    private static Exit exit(OpenPosition position, CloseReason reason, BigDecimal price, PriceRange range) {
        return new Exit(position.planId(), reason, price, range.from());
    }
}
