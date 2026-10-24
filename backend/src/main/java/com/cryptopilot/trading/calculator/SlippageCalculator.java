package com.cryptopilot.trading.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The price a taker fill is simulated at: its reference price moved against the Trader by {@code SLIPPAGE_BPS} basis
 * points, a buy up and a sell down, and rounded to the tick against the Trader (a buy up, a sell down). Zero basis
 * points leave the reference price as it is. Only taker fills slip: a maker fill rested at its own price, and a
 * liquidation exits at the liquidation price.
 *
 * <p>Rule: BR-36; D-111 E5; D-112 R6; TECHNICAL_DESIGN 7.8.
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 and 6 (an order that takes liquidity pays the spread and its price impact).
 */
public final class SlippageCalculator {

    private static final BigDecimal BASIS_POINTS = new BigDecimal("10000");

    private SlippageCalculator() {}

    /**
     * The fill of a taker order at {@code reference}.
     *
     * <p>Rule: BR-36; D-111 E5.
     *
     * @param buy {@code true} for a buy (a LONG entry, a SHORT exit), {@code false} for a sell
     * @param basisPoints the slippage, 0 or more
     * @param tickSize the pair's price increment
     */
    public static BigDecimal takerFill(BigDecimal reference, boolean buy, int basisPoints, BigDecimal tickSize) {
        Objects.requireNonNull(reference, "reference");
        if (basisPoints < 0) {
            throw new IllegalArgumentException("slippage must not be negative, was " + basisPoints + " bps");
        }
        if (basisPoints == 0) {
            return reference;
        }
        BigDecimal move = Rounding.divide(BigDecimal.valueOf(basisPoints), BASIS_POINTS);
        BigDecimal moved = reference.multiply(buy ? BigDecimal.ONE.add(move) : BigDecimal.ONE.subtract(move));
        return Rounding.toTickSize(moved, tickSize, buy ? RoundingMode.CEILING : RoundingMode.FLOOR);
    }

    /**
     * The fill of a LIMIT entry the last price already reached at activation: a taker fill at the last price, but
     * never worse than the limit price, which a LIMIT order never fills beyond.
     *
     * <p>Rule: BR-33, BR-36; D-111 E5; D-112 note (team lead, 2026-10-24).
     *
     * @param lastPrice the last price that reached the limit
     * @param limitPrice the plan's limit price
     * @param direction LONG buys, SHORT sells
     */
    public static BigDecimal marketableLimitFill(
            BigDecimal lastPrice, BigDecimal limitPrice, Direction direction, int basisPoints, BigDecimal tickSize) {
        boolean buy = buysOnEntry(direction);
        BigDecimal fill = takerFill(lastPrice, buy, basisPoints, tickSize);
        return buy ? fill.min(limitPrice) : fill.max(limitPrice);
    }

    /** Whether the entry of a position in this direction is a buy: LONG buys in, SHORT sells in. */
    public static boolean buysOnEntry(Direction direction) {
        return Objects.requireNonNull(direction, "direction") == Direction.LONG;
    }
}
