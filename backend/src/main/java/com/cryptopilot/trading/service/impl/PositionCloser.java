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
 *   <li>The exit price: a take profit and a liquidation at their level; a stop loss, a stop-market order, at its
 *       reference price moved by {@code SLIPPAGE_BPS} against the Trader.
 *   <li>{@code fee_amount} = the entry fee stored at the open + the exit fee by its liquidity role (take profit maker,
 *       stop loss taker), or the clearance fee for a liquidation, with the bracket of the entry notional.
 *   <li>The net profit and loss is {@link TradeAmounts#netPnl} of the stored amounts, never another formula.
 * </ul>
 *
 * <p>Rule: BR-35, BR-36, BR-37, BR-38; D-97 and its note of 2026-10-24; D-111 E1, E5; Q-56, Q-57, Q-58.
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
        // To the microsecond, like the column, so the funding period and the stored time agree.
        Instant exitTime = exit.exitedAt().truncatedTo(ChronoUnit.MICROS);
        BigDecimal exitPrice = exit.reason() == CloseReason.STOP_LOSS
                ? slippage.fill(
                        exit.price(),
                        !SlippageCalculator.buysOnEntry(trade.direction()),
                        trade.market(),
                        trade.pairId(),
                        "the stop loss of position " + trade.positionId())
                : exit.price();
        FundingFee fundingFee = trade.market() == MarketType.SPOT
                ? null
                : funding.fundingFee(trade.pairId(), trade.direction(), trade.quantity(), trade.entryTime(), exitTime);
        TradeResult result = TradeResultCalculator.calculate(
                new TradeClose(
                        trade.market(),
                        trade.direction(),
                        trade.entryLiquidity(),
                        trade.entryPrice(),
                        trade.quantity(),
                        exit.reason(),
                        exitPrice,
                        feeRates.of(trade.market()),
                        exit.reason() == CloseReason.LIQUIDATION ? brackets(trade) : List.of()),
                fundingFee);
        // The entry fee is the one stored at the open, at the rate of that moment.
        TradeAmounts amounts = new TradeAmounts(
                result.realizedPnl(),
                trade.entryFee().add(result.exitFee()),
                result.fundingFeeAmount(),
                result.fundingIncomplete());
        boolean closed = journal.closeSimulatedTrade(new SimulatedTradeClosing(
                trade.positionId(),
                trade.version(),
                exitPrice,
                exitTime,
                exit.reason(),
                amounts.realizedPnl(),
                amounts.feeAmount(),
                amounts.fundingFeeAmount(),
                amounts.fundingIncomplete(),
                result.missingFundingTimes()));
        if (closed) {
            NetPnl net = amounts.netPnl();
            events.publishEvent(new PositionClosed(
                    trade.positionId(),
                    trade.planId(),
                    trade.userId(),
                    trade.market(),
                    market.tradablePair(trade.pairId(), trade.market())
                            .map(TradablePair::symbol)
                            .orElse(null),
                    exit.reason(),
                    exitPrice,
                    amounts.realizedPnl(),
                    amounts.feeAmount(),
                    net.amount(),
                    net.provisional(),
                    exitTime));
            log.info(
                    "BR-35 position {} of plan {} closed by {} at {}, net {}{}",
                    trade.positionId(),
                    trade.planId(),
                    exit.reason(),
                    exitPrice,
                    net.amount(),
                    net.provisional() ? " (provisional)" : "");
        }
        return closed;
    }

    private List<LeverageBracket> brackets(OpenTrade trade) {
        return market.leverageBrackets(trade.pairId()).stream()
                .map(PlanCalculationAssembler::bracket)
                .toList();
    }
}
