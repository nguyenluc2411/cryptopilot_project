package com.cryptopilot.trading.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.trading.matching.ExitRule;
import com.cryptopilot.trading.model.EntryExpectation;
import com.cryptopilot.trading.model.FeeSchedule;
import com.cryptopilot.trading.model.PlanCosts;
import com.cryptopilot.trading.model.RiskCalculation;
import com.cryptopilot.trading.model.RiskInput;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;

/**
 * What a sized plan is expected to pay in fees and slippage before it is activated, with the same fee of a fill
 * ({@link FeeSchedule#fee}), the same liquidity roles and the same slippage ({@link SlippageCalculator}) as the
 * simulated fills. The quantity is not re-sized for them (BR-23): they are shown and warned about (HIGH_FEE_SHARE).
 *
 * <pre>
 * entry fee      = rate(expected entry role) × quantity × entry
 * SL exit fee    = taker × quantity × stop loss
 * TP exit fee    = maker × quantity × take profit
 * entry slippage = quantity × |entry fill − reference|      taker entry only
 * SL slippage    = quantity × |stop loss − SL fill|
 * loss at SL     = risk amount + entry fee + SL exit fee + SL slippage + entry slippage (*)
 * fee share      = (loss at SL − risk amount) / risk amount
 * net R:R        = (reward amount − entry fee − TP exit fee − entry slippage (*)) / loss at SL
 * </pre>
 *
 * (*) Not for MARKET: its entry is sized on the price paid, slippage included (D-112 R6), so its entry slippage is
 * already in the risk and reward amounts. A LIMIT the last price already reaches fills at the last price with
 * slippage, never past its limit, and is sized on its limit, so its entry slippage is added. Funding and the
 * liquidation clearance fee are not included.
 *
 * <p>Rule: BR-23, BR-29, BR-36; D-111 E1, E5; D-112 R6 and its note (2026-10-24); D-116; TECHNICAL_DESIGN 7.5, 7.8.
 *
 * <p>Reference: Tharp, V. K. (2006). <i>Trade Your Way to Financial Freedom</i> (2nd ed.). McGraw-Hill (a loss in R
 * units: the costs of a trade make a full stop-loss cost more than 1R).
 * <p>Reference: Binance FAQ, <i>Binance Futures Fee Structure &amp; Fee Calculations</i> (fee = notional × rate).
 */
public final class PlanCostCalculator {

    private PlanCostCalculator() {}

    /** The costs of the sized plan whose entry is expected to fill as {@code entry}, with the market's rates. */
    public static PlanCosts calculate(RiskInput plan, RiskCalculation sized, EntryExpectation entry, FeeSchedule fees) {
        BigDecimal quantity = sized.quantity();
        BigDecimal entryFee = fees.fee(entry.liquidity(), plan.entryPrice(), quantity);
        BigDecimal stopLossFee = fees.fee(ExitRule.liquidity(CloseReason.STOP_LOSS), plan.stopLoss(), quantity);
        BigDecimal takeProfitFee = fees.fee(ExitRule.liquidity(CloseReason.TAKE_PROFIT), plan.takeProfit(), quantity);

        BigDecimal entrySlippage = quantity.multiply(entrySlippagePerUnit(plan, entry));
        BigDecimal stopLossFill = SlippageCalculator.takerFill(
                plan.stopLoss(),
                !SlippageCalculator.buysOnEntry(plan.direction()),
                entry.slippageBps(),
                plan.filters().tickSize());
        BigDecimal stopLossSlippage =
                quantity.multiply(plan.stopLoss().subtract(stopLossFill).abs());
        BigDecimal entrySlippageOutsideSizing = entry.entryType() == EntryType.MARKET ? BigDecimal.ZERO : entrySlippage;

        BigDecimal lossAtStopLoss = sized.riskAmount()
                .add(entryFee)
                .add(stopLossFee)
                .add(stopLossSlippage)
                .add(entrySlippageOutsideSizing);
        BigDecimal netReward =
                sized.rewardAmount().subtract(entryFee).subtract(takeProfitFee).subtract(entrySlippageOutsideSizing);
        return new PlanCosts(
                entryFee,
                stopLossFee,
                takeProfitFee,
                entrySlippage.add(stopLossSlippage),
                lossAtStopLoss,
                Rounding.divide(lossAtStopLoss.subtract(sized.riskAmount()), sized.riskAmount()),
                Rounding.divide(netReward, lossAtStopLoss));
    }

    /**
     * How far a taker entry fills from its reference price: MARKET at the entry it was sized on, a marketable LIMIT at
     * the last price with slippage up to its limit, STOP at its stop price with slippage. A maker entry has none.
     */
    private static BigDecimal entrySlippagePerUnit(RiskInput plan, EntryExpectation entry) {
        if (entry.liquidity() == LiquidityRole.MAKER) {
            return BigDecimal.ZERO;
        }
        BigDecimal fill =
                switch (entry.entryType()) {
                    case MARKET -> plan.entryPrice();
                    case LIMIT ->
                        SlippageCalculator.marketableLimitFill(
                                entry.reference(),
                                plan.entryPrice(),
                                plan.direction(),
                                entry.slippageBps(),
                                plan.filters().tickSize());
                    case STOP ->
                        SlippageCalculator.takerFill(
                                entry.reference(),
                                SlippageCalculator.buysOnEntry(plan.direction()),
                                entry.slippageBps(),
                                plan.filters().tickSize());
                };
        return fill.subtract(entry.reference()).abs();
    }
}
