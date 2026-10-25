package com.cryptopilot.trading.model;

import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The most adverse and the most favourable last price an open position has seen since its fill.
 *
 * <ul>
 *   <li>Both start at the fill price.
 *   <li>The entry candle adds only its adverse extreme, and nothing to the favourable one: the order of the prices
 *       inside the minute is unknown, also when the entry filled at the candle's open.
 *   <li>The exit candle is clamped at the exit price on the side the exit was reached: a take profit caps the
 *       favourable extreme, a stop loss or a liquidation the adverse one.
 * </ul>
 *
 * <p>Rule: BR-70; D-111 M4; D-112 R5; TECHNICAL_DESIGN 7.7 and 7.8.
 *
 * <p>Reference: Sweeney, J. (1996). <i>Maximum Adverse Excursion: Analyzing Price Fluctuations for Trading
 * Management</i>. Wiley.
 *
 * @param adverse the lowest price of a LONG, the highest of a SHORT
 * @param favorable the highest price of a LONG, the lowest of a SHORT
 */
public record Excursion(BigDecimal adverse, BigDecimal favorable) {

    public Excursion {
        Objects.requireNonNull(adverse, "adverse");
        Objects.requireNonNull(favorable, "favorable");
    }

    /** The excursion of a position that has just filled at {@code fillPrice}. */
    public static Excursion at(BigDecimal fillPrice) {
        return new Excursion(fillPrice, fillPrice);
    }

    /** This excursion widened by a range of last prices; {@code entryCandle} adds only the adverse extreme. */
    public Excursion with(Direction direction, PriceRange range, boolean entryCandle) {
        BigDecimal adverseExtreme = direction == Direction.LONG ? range.low() : range.high();
        BigDecimal favorableExtreme = direction == Direction.LONG ? range.high() : range.low();
        return new Excursion(
                worse(direction, adverse, adverseExtreme),
                entryCandle ? favorable : better(direction, favorable, favorableExtreme));
    }

    /**
     * This excursion widened by the candle the position exited in, each extreme of that candle clamped at the exit
     * price on the side the exit was reached.
     */
    public Excursion withExit(
            Direction direction, PriceRange range, boolean entryCandle, CloseReason reason, BigDecimal exitPrice) {
        boolean isLong = direction == Direction.LONG;
        BigDecimal low = range.low();
        BigDecimal high = range.high();
        boolean adverseExit = reason != CloseReason.TAKE_PROFIT;
        if (adverseExit == isLong) {
            low = low.max(exitPrice).min(high);
        } else {
            high = high.min(exitPrice).max(low);
        }
        return with(
                direction,
                new PriceRange(
                        range.market(),
                        range.pairId(),
                        range.open(),
                        low,
                        high,
                        range.from(),
                        range.at(),
                        range.closed()),
                entryCandle);
    }

    private static BigDecimal worse(Direction direction, BigDecimal current, BigDecimal candidate) {
        return direction == Direction.LONG ? current.min(candidate) : current.max(candidate);
    }

    private static BigDecimal better(Direction direction, BigDecimal current, BigDecimal candidate) {
        return direction == Direction.LONG ? current.max(candidate) : current.min(candidate);
    }
}
