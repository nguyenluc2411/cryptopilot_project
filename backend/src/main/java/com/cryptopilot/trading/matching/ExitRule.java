package com.cryptopilot.trading.matching;

import com.cryptopilot.trading.model.Exit;
import com.cryptopilot.trading.model.OpenPosition;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * When an open position exits and at what price.
 *
 * <ul>
 *   <li>Take profit and stop loss by the last price range: a LONG stop at or below its level, a LONG take profit at
 *       or above it, and the mirror for SHORT. Both bounds count; the exit is at the level.
 *   <li>When one range reaches both, the stop loss is applied.
 *   <li>Liquidation by the mark price only, at the liquidation price.
 * </ul>
 *
 * <p>Rule: BR-35; NSF-07; TECHNICAL_DESIGN 7.7; D-78.
 */
public final class ExitRule {

    private ExitRule() {}

    /** The take profit or stop loss the range reached, stop loss first; recorded at the candle's open time. */
    public static Optional<Exit> byRange(OpenPosition position, PriceRange range) {
        boolean isLong = position.direction() == Direction.LONG;
        BigDecimal stop = position.stopLoss();
        if (stop != null
                && (isLong ? range.low().compareTo(stop) <= 0 : range.high().compareTo(stop) >= 0)) {
            return Optional.of(new Exit(position.planId(), CloseReason.STOP_LOSS, stop, range.from()));
        }
        BigDecimal target = position.takeProfit();
        if (target != null
                && (isLong ? range.high().compareTo(target) >= 0 : range.low().compareTo(target) <= 0)) {
            return Optional.of(new Exit(position.planId(), CloseReason.TAKE_PROFIT, target, range.from()));
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
}
