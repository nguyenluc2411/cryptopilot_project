package com.cryptopilot.trading.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.FeeSchedule;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.LeverageBracket;
import com.cryptopilot.trading.model.TradeClose;
import com.cryptopilot.trading.model.TradeResult;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;

/**
 * The realized profit and loss and the fees of a closed position. Realized is the price result only. Each fill pays
 * notional × rate: maker for a LIMIT entry and a take profit, taker for a MARKET entry, a stop loss and a manual
 * close. A liquidation pays the clearance fee instead of the taker fee. Funding is computed by
 * {@link FundingCalculator} and passed in.
 *
 * <p>Rule: BR-35, BR-36, BR-37, BR-38; D-97; Q-54; TECHNICAL_DESIGN 7.8.
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
        BigDecimal priceMove = in.exitPrice().subtract(in.entryPrice());
        BigDecimal realized =
                (in.direction() == Direction.LONG ? priceMove : priceMove.negate()).multiply(in.quantity());
        FeeSchedule fees = in.fees();
        BigDecimal entryRate = in.entryType() == EntryType.LIMIT ? fees.maker() : fees.taker();
        BigDecimal entryFee = in.entryPrice().multiply(in.quantity()).multiply(entryRate);
        BigDecimal exitNotional = in.exitPrice().multiply(in.quantity());
        BigDecimal exitFee =
                switch (in.reason()) {
                    case TAKE_PROFIT -> exitNotional.multiply(fees.maker());
                    case STOP_LOSS, MANUAL -> exitNotional.multiply(fees.taker());
                    case LIQUIDATION -> clearanceFee(exitNotional, fees.liquidationClearance(), liquidationBracket(in));
                };
        return new TradeResult(
                Rounding.toAmount(realized), Rounding.toAmount(entryFee), Rounding.toAmount(exitFee), funding);
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
