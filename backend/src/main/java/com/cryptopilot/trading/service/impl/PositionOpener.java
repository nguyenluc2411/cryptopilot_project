package com.cryptopilot.trading.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.SimulatedTradeOpening;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.calculator.LiquidationCalculator;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.model.LeverageBracket;
import com.cryptopilot.trading.model.LiquidationEstimate;
import com.cryptopilot.trading.model.LiquidationInput;
import com.cryptopilot.trading.model.LiquidationOutcome;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.enums.PlanStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hands a plan that has just filled to the journal, inside the transaction of the fill, on every fill path: the
 * resting LIMIT and the STOP the engine fills, and the LIMIT or MARKET filled at activation.
 *
 * <p>Rule: BR-27, BR-34, BR-36; D-78, D-97, D-111 E1, D-119.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PositionOpener {

    private static final Logger log = LoggerFactory.getLogger(PositionOpener.class);

    private final TradeJournal journal;
    private final FeeRates feeRates;
    private final MarketApi market;

    /**
     * Records the OPEN position of an EXECUTED plan: its fill price, fill time and liquidity role, the snapshot's
     * quantity, the levels it starts with, the entry fee at the rate of that role, rounded to the stored scale as the
     * close rounds it, and, on Futures, the liquidation price at the fill price.
     *
     * <p>Rule: BR-27, BR-34, BR-36; D-97; D-111 E1; TECHNICAL_DESIGN 7.6 and 7.8.
     *
     * @return the position as the matching engine tracks it
     * @throws IllegalStateException when the plan is not EXECUTED
     */
    public TrackedPosition open(TradingPlan plan) {
        if (plan.getStatus() != PlanStatus.EXECUTED) {
            throw new IllegalStateException("plan " + plan.getId() + " is " + plan.getStatus() + ", not EXECUTED");
        }
        BigDecimal quantity = plan.getSnapshot().positionQuantity();
        BigDecimal entryFee = Rounding.toAmount(
                feeRates.of(plan.getMarket()).fee(plan.getFillLiquidity(), plan.getFillPrice(), quantity));
        return TrackedPosition.of(journal.openSimulatedTrade(new SimulatedTradeOpening(
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
                plan.getTakeProfit(),
                liquidationPrice(plan, quantity))));
    }

    /**
     * The liquidation price of a Futures position at its actual fill price, with the pair's brackets now: a STOP
     * filled through a gap, or a MARKET filled with slippage, is not liquidated where the plan was estimated. When it
     * cannot be computed (no brackets or tick size, or the filled notional outside the brackets) the plan's estimate
     * is kept. {@code null} for Spot and for a position no price liquidates.
     *
     * <p>Rule: BR-27; Q-58; TECHNICAL_DESIGN 7.6.
     */
    private BigDecimal liquidationPrice(TradingPlan plan, BigDecimal quantity) {
        if (plan.getMarket() != MarketType.FUTURES) {
            return null;
        }
        List<LeverageBracket> brackets = market.leverageBrackets(plan.getPairId()).stream()
                .map(PlanCalculationAssembler::bracket)
                .toList();
        Optional<BigDecimal> tickSize = market.tradablePair(plan.getPairId(), MarketType.FUTURES)
                .map(pair -> pair.filters().tickSize());
        BigDecimal liquidation = plan.getSnapshot().estimatedLiquidationPrice();
        if (!brackets.isEmpty() && tickSize.isPresent()) {
            LiquidationOutcome outcome = LiquidationCalculator.calculate(new LiquidationInput(
                    plan.getDirection(),
                    plan.getFillPrice(),
                    plan.getStopLoss(),
                    quantity,
                    plan.getLeverage(),
                    plan.getCapital(),
                    tickSize.get(),
                    brackets));
            if (outcome instanceof LiquidationEstimate estimate) {
                liquidation = estimate.liquidationPrice();
            } else {
                log.warn(
                        "BR-27 plan {} filled at {} outside its pair's brackets; its estimated liquidation price {} is"
                                + " kept",
                        plan.getId(),
                        plan.getFillPrice(),
                        liquidation);
            }
        }
        return liquidation == null || liquidation.signum() <= 0 ? null : liquidation;
    }
}
