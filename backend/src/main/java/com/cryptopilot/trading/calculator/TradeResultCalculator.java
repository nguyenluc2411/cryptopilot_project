package com.cryptopilot.trading.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.trading.model.FeeSchedule;
import com.cryptopilot.trading.model.LeverageBracket;
import com.cryptopilot.trading.model.TradeClose;
import com.cryptopilot.trading.model.TradeResult;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.util.List;

/**
 * The realized profit and loss and the fees of a closed position. Realized is the price result only. Each fill pays
 * notional × rate: maker for a LIMIT entry and a take profit, taker for a MARKET entry, a stop loss and a manual
 * close. A liquidation pays the clearance fee instead of the taker fee.
 *
 * <p>Rule: BR-35, BR-36, BR-38; D-97; Q-54; TECHNICAL_DESIGN 7.8.
 */
public final class TradeResultCalculator {

    private TradeResultCalculator() {}

    /** The amounts of the trade, rounded to the stored scale. */
    public static TradeResult calculate(TradeClose in) {
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
                    case LIQUIDATION -> clearanceFee(exitNotional, fees.liquidationClearance(), in.brackets());
                };
        return new TradeResult(Rounding.toAmount(realized), Rounding.toAmount(entryFee), Rounding.toAmount(exitFee));
    }

    /**
     * The liquidation clearance fee: notional at the liquidation price × rate, but never more than the maintenance
     * margin left in the position at that price. The bracket is the one that contains that notional.
     *
     * <p>Rule: BR-27, BR-36; D-97 rule 2 as amended by Q-54; TECHNICAL_DESIGN 7.6 and 7.8.
     * <p>Reference: Binance FAQ, <i>Binance Futures Liquidation Protocols</i> (clearance fee = notional × rate, taken
     * from the assets that maintain the position).
     * <p>Reference: Binance, <i>Trading Rules of Futures Contracts</i> (rate 1.25 % for BTCUSDT and ETHUSDT).
     * <p>Reference: Binance FAQ, <i>How to Calculate Profit and Loss for Futures Contracts</i> (realized PnL excludes
     * commission, funding and the clearance fee).
     *
     * @throws IllegalArgumentException when the notional is above the largest bracket (Q-58)
     */
    public static BigDecimal clearanceFee(
            BigDecimal notionalAtLiquidation, BigDecimal rate, List<LeverageBracket> brackets) {
        LeverageBracket bracket = LiquidationCalculator.bracketFor(brackets, notionalAtLiquidation)
                .orElseThrow(() -> new IllegalArgumentException("the notional " + notionalAtLiquidation
                        + " at the liquidation price is above the largest bracket"));
        BigDecimal maintenanceMargin =
                bracket.maintenanceMargin(notionalAtLiquidation).max(BigDecimal.ZERO);
        return notionalAtLiquidation.multiply(rate).min(maintenanceMargin);
    }
}
