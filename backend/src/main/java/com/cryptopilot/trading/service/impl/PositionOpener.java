package com.cryptopilot.trading.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.trading.SimulatedTradeOpening;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.model.enums.PlanStatus;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Hands a plan that has just filled to the journal, inside the transaction of the fill, on every fill path: the
 * resting LIMIT the engine fills and the LIMIT or MARKET filled at activation.
 *
 * <p>Rule: BR-34, BR-36; D-78, D-97, D-111 E1, D-119.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PositionOpener {

    private final TradeJournal journal;
    private final FeeRates feeRates;

    /**
     * Records the OPEN position of an EXECUTED plan: its fill price, fill time and liquidity role, the snapshot's
     * quantity, the levels it starts with, and the entry fee at the rate of that role, rounded to the stored scale as
     * the close rounds it.
     *
     * <p>Rule: BR-34, BR-36; D-97; D-111 E1; TECHNICAL_DESIGN 7.8.
     *
     * @throws IllegalStateException when the plan is not EXECUTED
     */
    public void open(TradingPlan plan) {
        if (plan.getStatus() != PlanStatus.EXECUTED) {
            throw new IllegalStateException("plan " + plan.getId() + " is " + plan.getStatus() + ", not EXECUTED");
        }
        BigDecimal quantity = plan.getSnapshot().positionQuantity();
        BigDecimal entryFee = Rounding.toAmount(
                feeRates.of(plan.getMarket()).fee(plan.getFillLiquidity(), plan.getFillPrice(), quantity));
        journal.openSimulatedTrade(new SimulatedTradeOpening(
                plan.getId(),
                plan.getUserId(),
                plan.getPairId(),
                plan.getMarket(),
                plan.getDirection(),
                plan.getLeverage(),
                plan.getFillPrice(),
                plan.getExecutedAt(),
                quantity,
                plan.getFillLiquidity(),
                entryFee,
                plan.getStopLoss(),
                plan.getTakeProfit()));
    }
}
