package com.cryptopilot.trading.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.matching.ExitRule;
import com.cryptopilot.trading.model.FeeSchedule;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.LeverageBracket;
import com.cryptopilot.trading.model.TradeClose;
import com.cryptopilot.trading.model.TradeResult;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;

/**
 * The realized profit and loss and the fees of a closed position. Realized is the price result only. Each fill pays
 * notional × the rate of its liquidity role: the entry by the role stored with its fill, a take profit maker, a stop
 * loss and a manual close taker. A liquidation pays the clearance fee instead. Funding is computed by
 * {@link FundingCalculator} and passed in.
 *
 * <p>Rule: BR-35, BR-36, BR-37, BR-38; D-97; D-111 E1; Q-54; TECHNICAL_DESIGN 7.8.
 */
public final class TradeResultCalculator {

    private TradeResultCalculator() {}

    /**
     * The amounts of the trade, rounded to the stored scale.
     *
     * @param funding the funding of a Futures trade from {@link FundingCalculator}, complete or not: an incomplete
     *     funding never blocks the close, the result carries it and its net is provisional (A-05); {@code null} for
     *     Spot, which has none (BR-37)
     */
    public static TradeResult calculate(TradeClose in, FundingFee funding) {
        if ((in.market() == MarketType.SPOT) != (funding == null)) {
            throw new IllegalArgumentException("a Futures trade needs its funding and a Spot trade has none");
        }
        BigDecimal realized = priceResult(in.direction(), in.entryPrice(), in.exitPrice(), in.quantity());
        FeeSchedule fees = in.fees();
        BigDecimal entryFee = fees.fee(in.entryLiquidity(), in.entryPrice(), in.quantity());
        BigDecimal exitFee = in.reason() == CloseReason.LIQUIDATION
                ? clearanceFee(
                        in.exitPrice().multiply(in.quantity()), fees.liquidationClearance(), liquidationBracket(in))
                : fees.fee(ExitRule.liquidity(in.reason()), in.exitPrice(), in.quantity());
        return new TradeResult(
                Rounding.toAmount(realized), Rounding.toAmount(entryFee), Rounding.toAmount(exitFee), funding);
    }

    /**
     * The unrealized P/L of an open position at the current price, rounded to the stored scale: the price result the
     * position would realize if it closed there, without fees or funding. The current price is the Futures mark price
     * or the Spot last price.
     *
     * <p>Rule: BR-35; UC-20; Q-55 (the live P/L of SCR-18 is unrealized by mark, no fees); TECHNICAL_DESIGN 7.8.
     * <p>Reference: Binance FAQ, <i>How to Calculate Profit and Loss for Futures Contracts</i> (unrealized PnL =
     * position size × direction × (mark price − entry price)).
     *
     * @param direction LONG gains when the price rises, SHORT when it falls
     * @param entryPrice the actual fill price
     * @param currentPrice the Futures mark price or the Spot last price
     * @param quantity the quantity held
     */
    public static BigDecimal unrealizedPnl(
            Direction direction, BigDecimal entryPrice, BigDecimal currentPrice, BigDecimal quantity) {
        return Rounding.toAmount(priceResult(direction, entryPrice, currentPrice, quantity));
    }

    /** LONG {@code (exit − entry) × qty}, SHORT {@code (entry − exit) × qty}, at full precision (BR-35, D-97 rule 1). */
    private static BigDecimal priceResult(
            Direction direction, BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal quantity) {
        BigDecimal priceMove = exitPrice.subtract(entryPrice);
        return (direction == Direction.LONG ? priceMove : priceMove.negate()).multiply(quantity);
    }

    /**
     * The liquidation clearance fee: notional at the liquidation price × rate, but never more than the maintenance
     * margin at that notional. The maintenance margin uses the bracket the liquidation price was derived from, so the
     * fee never exceeds the margin left in the position.
     *
     * <p>Rule: BR-27, BR-36; D-97 rule 2 as amended by Q-54; Q-58; TECHNICAL_DESIGN 7.6 and 7.8.
     * <p>Reference: Binance FAQ, <i>Binance Futures Liquidation Protocols</i> (clearance fee = notional × rate, taken
     * from the assets that maintain the position).
     * <p>Reference: Binance, <i>Trading Rules of Futures Contracts</i> (rate 1.25 % for BTCUSDT and ETHUSDT).
     * <p>Reference: Binance FAQ, <i>How to Calculate Profit and Loss for Futures Contracts</i> (realized PnL excludes
     * commission, funding and the clearance fee).
     *
     * @param liquidationBracket the bracket of the entry notional, from {@link LiquidationCalculator#positionBracket}
     */
    public static BigDecimal clearanceFee(
            BigDecimal notionalAtLiquidation, BigDecimal rate, LeverageBracket liquidationBracket) {
        BigDecimal maintenanceMargin =
                liquidationBracket.maintenanceMargin(notionalAtLiquidation).max(BigDecimal.ZERO);
        return notionalAtLiquidation.multiply(rate).min(maintenanceMargin);
    }

    // Outside the brackets the position never had a liquidation price (MSG15).
    private static LeverageBracket liquidationBracket(TradeClose in) {
        return LiquidationCalculator.positionBracket(in.brackets(), in.quantity(), in.entryPrice())
                .orElseThrow(() -> new IllegalArgumentException(
                        "the entry notional is outside the brackets, so the position has no liquidation price"));
    }
}
