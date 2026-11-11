package com.cryptopilot.paper.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.paper.model.FuturesWarning;
import com.cryptopilot.paper.model.enums.FuturesWarningType;
import com.cryptopilot.paper.model.enums.PositionDirection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The risk warnings of an order that opens or increases a paper Futures position (TR-26), worked out from figures the
 * caller gathers. The rules are the trading plan's (BR-28, BR-29) on the same thresholds, written again here because
 * paper trading does not reach the plan module until the two engines are one (D-117):
 *
 * <ul>
 *   <li>INSUFFICIENT_MARGIN: the cost above the balance it is paid from (4.6 … 4.8).
 *   <li>SL_BEYOND_LIQUIDATION: a long's stop loss at or below the estimated liquidation price, a short's at or above;
 *       never when no price liquidates the position.
 *   <li>HIGH_LEVERAGE: above the profile's maximum leverage.
 *   <li>OVERSIZED_POSITION: the loss at the stop loss, {@code |E − SL| × Q}, above the profile's risk per trade of the
 *       wallet.
 *   <li>LOW_RR: {@code |TP − E| / |E − SL|} strictly below the minimum.
 *   <li>HIGH_FUNDING_RATE: {@code |rate|} at or above the threshold and the side pays it (a long a positive rate).
 *   <li>WIDE_STOP_LOSS: {@code |E − SL| / E × 100} strictly above the percentage.
 * </ul>
 *
 * <p>The warnings come the most severe first. Pure: no state, no clock.
 *
 * <p>Rule: TR-26; BR-28, BR-29, BR-66; D-117.
 * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill (the stop
 * distance as the initial risk; position sizing as a share of equity).
 */
public final class FuturesOrderWarnings {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final Comparator<FuturesWarning> MOST_SEVERE_FIRST =
            Comparator.comparing(FuturesWarning::severity).reversed().thenComparing(FuturesWarning::type);

    private FuturesOrderWarnings() {}

    /** The warnings the order raises, the most severe first; empty when it raises none. */
    public static List<FuturesWarning> evaluate(Input in) {
        List<FuturesWarning> warnings = new ArrayList<>();
        if (in.cost().compareTo(in.budget()) > 0) {
            warnings.add(new FuturesWarning(
                    FuturesWarningType.INSUFFICIENT_MARGIN,
                    "the order costs " + show(in.cost()) + " USDT, more than the " + show(in.budget()) + " available"));
        }
        if (in.leverage() > in.profileMaxLeverage()) {
            warnings.add(new FuturesWarning(
                    FuturesWarningType.HIGH_LEVERAGE,
                    "leverage " + in.leverage() + "x is above the risk profile's maximum of " + in.profileMaxLeverage()
                            + "x"));
        }
        BigDecimal sign = in.direction().sign();
        BigDecimal rate = in.fundingRate();
        if (rate != null && rate.abs().compareTo(in.highFundingRate()) >= 0 && rate.signum() == sign.signum()) {
            warnings.add(new FuturesWarning(
                    FuturesWarningType.HIGH_FUNDING_RATE,
                    "the funding rate " + rate.stripTrailingZeros().toPlainString() + " is paid by a " + in.direction()
                            + " position"));
        }
        if (in.stopLoss() != null) {
            stopLossWarnings(in, warnings);
        }
        warnings.sort(MOST_SEVERE_FIRST);
        return List.copyOf(warnings);
    }

    private static void stopLossWarnings(Input in, List<FuturesWarning> warnings) {
        BigDecimal stop = in.stopLoss();
        BigDecimal liquidation = in.liquidationPrice();
        if (liquidation.signum() > 0) {
            boolean beyond = in.direction() == PositionDirection.LONG
                    ? stop.compareTo(liquidation) <= 0
                    : stop.compareTo(liquidation) >= 0;
            if (beyond) {
                warnings.add(new FuturesWarning(
                        FuturesWarningType.SL_BEYOND_LIQUIDATION,
                        "the stop loss " + show(stop) + " is at or beyond the estimated liquidation price "
                                + show(liquidation)));
            }
        }
        BigDecimal distance = in.entryPrice().subtract(stop).abs();
        if (in.walletBalance().signum() > 0) {
            BigDecimal riskPercent =
                    Rounding.divide(distance.multiply(in.quantity()).multiply(HUNDRED), in.walletBalance());
            if (riskPercent.compareTo(in.riskPerTradePercent()) > 0) {
                warnings.add(new FuturesWarning(
                        FuturesWarningType.OVERSIZED_POSITION,
                        "the loss at the stop loss is " + show(riskPercent) + "% of the wallet, above the risk"
                                + " profile's " + show(in.riskPerTradePercent()) + "% per trade"));
            }
        }
        if (in.takeProfit() != null && distance.signum() > 0) {
            BigDecimal ratio =
                    Rounding.divide(in.takeProfit().subtract(in.entryPrice()).abs(), distance);
            if (ratio.compareTo(in.minRiskRewardRatio()) < 0) {
                warnings.add(new FuturesWarning(
                        FuturesWarningType.LOW_RR,
                        "the reward to risk ratio " + show(ratio) + " is below " + show(in.minRiskRewardRatio())));
            }
        }
        BigDecimal distancePercent = Rounding.divide(distance.multiply(HUNDRED), in.entryPrice());
        if (distancePercent.compareTo(in.wideStopLossPercent()) > 0) {
            warnings.add(new FuturesWarning(
                    FuturesWarningType.WIDE_STOP_LOSS,
                    "the stop loss is " + show(distancePercent) + "% from the entry, more than "
                            + show(in.wideStopLossPercent()) + "%"));
        }
    }

    /** Truncated to four decimals for display; the rules compare at full precision. */
    private static String show(BigDecimal value) {
        return value.setScale(4, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }

    /**
     * What the rules read.
     *
     * @param direction the side the order opens or increases
     * @param entryPrice the price it is expected to open at: its limit price, or the mark for a MARKET order
     * @param quantity the part of the order that opens or increases the position
     * @param leverage the pair's leverage
     * @param cost the order's cost, initial margin plus open loss (4.6)
     * @param budget the balance the cost is paid from: available for cross, the cross wallet's free cash for isolated
     * @param walletBalance the Futures wallet, the capital the risk per trade is a share of
     * @param liquidationPrice the estimated liquidation price of the position once the order filled; zero for none
     * @param stopLoss the stop loss the form carries, or {@code null}
     * @param takeProfit the take profit the form carries, or {@code null}
     * @param fundingRate the coming funding rate, or {@code null} when not known
     * @param profileMaxLeverage the risk profile's maximum Futures leverage
     * @param riskPerTradePercent the risk profile's risk per trade, in percent
     * @param minRiskRewardRatio {@code WARN_LOW_RR_RATIO}
     * @param wideStopLossPercent {@code WARN_WIDE_STOP_LOSS_PERCENT}
     * @param highFundingRate {@code WARN_HIGH_FUNDING_RATE}
     */
    public record Input(
            PositionDirection direction,
            BigDecimal entryPrice,
            BigDecimal quantity,
            int leverage,
            BigDecimal cost,
            BigDecimal budget,
            BigDecimal walletBalance,
            BigDecimal liquidationPrice,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            BigDecimal fundingRate,
            int profileMaxLeverage,
            BigDecimal riskPerTradePercent,
            BigDecimal minRiskRewardRatio,
            BigDecimal wideStopLossPercent,
            BigDecimal highFundingRate) {

        public Input {
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(entryPrice, "entryPrice");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(cost, "cost");
            Objects.requireNonNull(budget, "budget");
            Objects.requireNonNull(walletBalance, "walletBalance");
            Objects.requireNonNull(liquidationPrice, "liquidationPrice");
            Objects.requireNonNull(riskPerTradePercent, "riskPerTradePercent");
            Objects.requireNonNull(minRiskRewardRatio, "minRiskRewardRatio");
            Objects.requireNonNull(wideStopLossPercent, "wideStopLossPercent");
            Objects.requireNonNull(highFundingRate, "highFundingRate");
        }
    }
}
