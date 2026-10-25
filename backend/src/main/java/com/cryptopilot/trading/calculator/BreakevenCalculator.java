package com.cryptopilot.trading.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.FeeSchedule;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.TradeAmounts;
import com.cryptopilot.trading.model.TradeClose;
import com.cryptopilot.trading.model.TradeResult;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The true breakeven stop of an open position: the stop loss price on the pair's tick at which a stop-out ends at a net
 * profit and loss of zero or more, while a stop one tick closer to the entry ends below zero. A stop at the entry price
 * still loses the fees, so this price lies beyond the entry by the entry fee already paid, the taker fee of the exit,
 * its slippage and, on Futures, the funding so far.
 *
 * <p>No fee formula of its own: each candidate stop is closed the way the matching engine closes a stop loss, with
 * {@link SlippageCalculator#takerFill}, {@link TradeResultCalculator#calculate} and {@link TradeAmounts#netPnl}, and the
 * candidates are searched on the tick grid. The net is monotone in the stop price, so an exponential search followed by
 * a binary search finds the price in a number of steps logarithmic in its distance from the entry. Rounded against the
 * Trader: up for a LONG, down for a SHORT.
 *
 * <p>Rule: BR-35, BR-36, BR-37, BR-38; D-97, D-122; D-124 (TP-E).
 *
 * <p>Reference: Bentley, J. L. &amp; Yao, A. C. (1976). An almost optimal algorithm for unbounded searching.
 * <i>Information Processing Letters</i>, 5(3), 82–87 (exponential search over an unbounded monotone sequence).
 */
public final class BreakevenCalculator {

    /** Enough doublings for any price a pair can have on its tick; a search that needs more has no breakeven. */
    private static final int MAX_DOUBLINGS = 62;

    private BreakevenCalculator() {}

    /**
     * The breakeven stop of the position.
     *
     * <p>Rule: BR-38; D-124.
     *
     * @param market SPOT or FUTURES
     * @param direction LONG or SHORT
     * @param entryLiquidity the liquidity role of the entry fill
     * @param entryPrice the stored entry fill price
     * @param quantity the quantity held
     * @param entryFee the fee stored for the entry fill
     * @param fees the fee rates of the market; the exit is a taker
     * @param slippageBps the simulated slippage of the stop-market exit
     * @param tickSize the pair's price increment
     * @param fundingSoFar the funding of a Futures position from its entry to now; {@code null} for Spot
     * @return the price, or empty when no positive price on the tick reaches a net of zero, or every one does
     */
    public static Optional<BigDecimal> breakevenStop(
            MarketType market,
            Direction direction,
            LiquidityRole entryLiquidity,
            BigDecimal entryPrice,
            BigDecimal quantity,
            BigDecimal entryFee,
            FeeSchedule fees,
            int slippageBps,
            BigDecimal tickSize,
            FundingFee fundingSoFar) {
        Objects.requireNonNull(entryFee, "entryFee");
        Objects.requireNonNull(tickSize, "tickSize");
        if (tickSize.signum() <= 0) {
            throw new IllegalArgumentException("the tick size must be above 0, was " + tickSize);
        }
        boolean isLong = direction == Direction.LONG;
        // k ticks in the Trader's favour from the entry on the grid; the net never falls as k grows.
        BigDecimal base = Rounding.toTickSize(entryPrice, tickSize, isLong ? RoundingMode.CEILING : RoundingMode.FLOOR);
        Net net = k -> {
            BigDecimal stop = base.add(tickSize.multiply(BigDecimal.valueOf(isLong ? k : -k)));
            return stop.signum() <= 0
                    ? null
                    : netAt(
                            market,
                            direction,
                            entryLiquidity,
                            entryPrice,
                            quantity,
                            entryFee,
                            fees,
                            slippageBps,
                            tickSize,
                            fundingSoFar,
                            stop);
        };
        long low;
        long high;
        BigDecimal atBase = net.at(0);
        if (atBase != null && atBase.signum() >= 0) {
            // The funding received already covers the fees: look for the first loss on the other side of the entry.
            high = 0;
            low = -1;
            for (int i = 0; ; i++) {
                BigDecimal value = net.at(low);
                if (value == null || i == MAX_DOUBLINGS) {
                    // Not even the lowest positive price loses: no stop is needed to stay at zero.
                    return Optional.empty();
                }
                if (value.signum() < 0) {
                    break;
                }
                high = low;
                low *= 2;
            }
        } else {
            low = 0;
            high = 1;
            for (int i = 0; ; i++) {
                BigDecimal value = net.at(high);
                if (value == null || i == MAX_DOUBLINGS) {
                    return Optional.empty();
                }
                if (value.signum() >= 0) {
                    break;
                }
                low = high;
                high *= 2;
            }
        }
        // Invariant: the net at low is below zero, the net at high is zero or above.
        while (high - low > 1) {
            long middle = low + (high - low) / 2;
            BigDecimal value = net.at(middle);
            if (value != null && value.signum() >= 0) {
                high = middle;
            } else {
                low = middle;
            }
        }
        return Optional.of(base.add(tickSize.multiply(BigDecimal.valueOf(isLong ? high : -high))));
    }

    /**
     * The net profit and loss of a stop-out at {@code stop}: the stop-market exit filled at the stop moved by the
     * slippage, its taker fee, the stored entry fee and the funding, as the matching engine closes a stop loss.
     *
     * <p>Rule: BR-36, BR-38; D-97, D-122 (2).
     */
    public static BigDecimal netAt(
            MarketType market,
            Direction direction,
            LiquidityRole entryLiquidity,
            BigDecimal entryPrice,
            BigDecimal quantity,
            BigDecimal entryFee,
            FeeSchedule fees,
            int slippageBps,
            BigDecimal tickSize,
            FundingFee fundingSoFar,
            BigDecimal stop) {
        BigDecimal exitPrice =
                SlippageCalculator.takerFill(stop, !SlippageCalculator.buysOnEntry(direction), slippageBps, tickSize);
        TradeResult result = TradeResultCalculator.calculate(
                new TradeClose(
                        market,
                        direction,
                        entryLiquidity,
                        entryPrice,
                        quantity,
                        CloseReason.STOP_LOSS,
                        exitPrice,
                        fees,
                        List.of()),
                fundingSoFar);
        return new TradeAmounts(
                        result.realizedPnl(),
                        entryFee.add(result.exitFee()),
                        result.fundingFeeAmount(),
                        result.fundingIncomplete())
                .netPnl()
                .amount();
    }

    /** The net at k ticks from the entry in the Trader's favour; {@code null} where the price is not positive. */
    @FunctionalInterface
    private interface Net {
        BigDecimal at(long k);
    }
}
