package com.cryptopilot.trading.service.impl;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.SimulatedTradeClosing;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.calculator.SlippageCalculator;
import com.cryptopilot.trading.calculator.TradeResultCalculator;
import com.cryptopilot.trading.event.PositionClosed;
import com.cryptopilot.trading.model.ClosingAmounts;
import com.cryptopilot.trading.model.Exit;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.LeverageBracket;
import com.cryptopilot.trading.model.NetPnl;
import com.cryptopilot.trading.model.PositionExit;
import com.cryptopilot.trading.model.TradeAmounts;
import com.cryptopilot.trading.model.TradeClose;
import com.cryptopilot.trading.model.TradeResult;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.service.FundingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Closes the journal row of a position the matching engine decided to exit, inside the caller's transaction. The
 * amounts come from {@link TradeResultCalculator}, the funding from {@link FundingService} (an incomplete funding
 * never blocks the close), and the close is a compare-and-set on the row's status and version; when it wins,
 * {@link PositionClosed} is published for after the commit.
 *
 * <ul>
 *   <li>The exit price: a take profit and a liquidation at their level; a stop loss, a stop-market order, and a
 *       manual close, a market order, at their reference price moved by {@code SLIPPAGE_BPS} against the Trader.
 *   <li>{@code fee_amount} = the entry fee stored at the open + the exit fee by its liquidity role (take profit maker,
 *       stop loss taker), or the clearance fee for a liquidation, with the bracket of the entry notional.
 *   <li>The net profit and loss is {@link TradeAmounts#netPnl} of the stored amounts, never another formula.
 * </ul>
 *
 * <p>Rule: BR-35, BR-36, BR-37, BR-38, BR-39; D-97 and its note of 2026-10-24; D-111 E1, E5; Q-56, Q-57, Q-58.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PositionCloser {

    private static final Logger log = LoggerFactory.getLogger(PositionCloser.class);

    private final TradeJournal journal;
    private final FeeRates feeRates;
    private final FundingService funding;
    private final TakerSlippage slippage;
    private final MarketApi market;
    private final ApplicationEventPublisher events;

    /**
     * Closes the position if it is still OPEN at the version the engine tracked.
     *
     * @return whether this call closed it; {@code false} when it was closed first, or its levels changed since
     */
    public boolean close(PositionExit decided) {
        Optional<OpenTrade> open = journal.openTrade(decided.position().positionId());
        if (open.isEmpty() || open.get().version() != decided.position().version()) {
            return false;
        }
        OpenTrade trade = open.get();
        Exit exit = decided.exit();
        ClosingAmounts closing = preview(trade, exit.reason(), exit.price(), exit.exitedAt());
        TradeAmounts amounts = closing.amounts();
        boolean closed = journal.closeSimulatedTrade(new SimulatedTradeClosing(
                trade.positionId(),
                trade.version(),
                closing.exitPrice(),
                closing.exitTime(),
                exit.reason(),
                amounts.realizedPnl(),
                amounts.feeAmount(),
                amounts.fundingFeeAmount(),
                amounts.fundingIncomplete(),
                closing.missingFundingTimes()));
        if (closed) {
            NetPnl net = amounts.netPnl();
            events.publishEvent(new PositionClosed(
                    trade.positionId(),
                    trade.planId(),
                    trade.userId(),
                    trade.market(),
                    trade.pairId(),
                    market.tradablePair(trade.pairId(), trade.market())
                            .map(TradablePair::symbol)
                            .orElse(null),
                    exit.reason(),
                    closing.exitPrice(),
                    amounts.realizedPnl(),
                    amounts.feeAmount(),
                    net.amount(),
                    net.provisional(),
                    closing.exitTime()));
            log.info(
                    "BR-35 position {} of plan {} closed by {} at {}, net {}{}",
                    trade.positionId(),
                    trade.planId(),
                    exit.reason(),
                    closing.exitPrice(),
                    net.amount(),
                    net.provisional() ? " (provisional)" : "");
        }
        return closed;
    }

    /**
     * What closing the position for {@code reason} at {@code reference} at {@code exitTime} stores, without storing it:
     * the exit price (a stop loss and a manual close are taker orders, moved by {@code SLIPPAGE_BPS} against the
     * Trader; a take profit and a liquidation are not), the amounts of {@link TradeResultCalculator} with the stored
     * entry fee, and the funding up to the exit. The close estimate of UC-21 is this same figure, so it equals the
     * stored close when the price has not moved.
     *
     * <p>Rule: BR-35, BR-36, BR-37, BR-38, BR-39; D-97; D-111 E5.
     *
     * @param reference the price before slippage: the level reached, or the current last price of a manual close
     */
    public ClosingAmounts preview(OpenTrade trade, CloseReason reason, BigDecimal reference, Instant exitTime) {
        // To the microsecond, like the column, so the funding period and the stored time agree.
        Instant at = exitTime.truncatedTo(ChronoUnit.MICROS);
        BigDecimal exitPrice = reason == CloseReason.STOP_LOSS || reason == CloseReason.MANUAL
                ? slippage.fill(
                        reference,
                        !SlippageCalculator.buysOnEntry(trade.direction()),
                        trade.market(),
                        trade.pairId(),
                        "the " + reason + " exit of position " + trade.positionId())
                : reference;
        FundingFee fundingFee = trade.market() == MarketType.SPOT
                ? null
                : funding.fundingFee(trade.pairId(), trade.direction(), trade.quantity(), trade.entryTime(), at);
        TradeResult result = TradeResultCalculator.calculate(
                new TradeClose(
                        trade.market(),
                        trade.direction(),
                        trade.entryLiquidity(),
                        trade.entryPrice(),
                        trade.quantity(),
                        reason,
                        exitPrice,
                        feeRates.of(trade.market()),
                        reason == CloseReason.LIQUIDATION ? brackets(trade) : List.of()),
                fundingFee);
        // The entry fee is the one stored at the open, at the rate of that moment.
        TradeAmounts amounts = new TradeAmounts(
                result.realizedPnl(),
                trade.entryFee().add(result.exitFee()),
                result.fundingFeeAmount(),
                result.fundingIncomplete());
        return new ClosingAmounts(exitPrice, at, result.exitFee(), amounts, result.missingFundingTimes());
    }

    private List<LeverageBracket> brackets(OpenTrade trade) {
        return market.leverageBrackets(trade.pairId()).stream()
                .map(PlanCalculationAssembler::bracket)
                .toList();
    }
}
