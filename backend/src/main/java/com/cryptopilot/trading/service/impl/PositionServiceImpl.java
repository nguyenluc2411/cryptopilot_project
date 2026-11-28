package com.cryptopilot.trading.service.impl;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.MarketHealth;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.PlanOrderPlacer;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.dto.response.CloseEstimateResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.exception.IllegalPlanStateException;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.PositionService;
import com.cryptopilot.trading.service.TradingPlanService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The estimate and the manual close of a plan's open position, held on the demo account since RF-33 (D-117, one
 * engine). Paper trading prices the close at the current last price (never the mark price): what it sells, its taker
 * fee and, on Futures, the funding the position settled; the stored entry fee comes off the net. The close is a MARKET
 * order whose fill closes the journal record (RF-35), so the estimate equals the stored close when the price has not
 * moved. A record of the plans' former engine, from before V77, has no paper position and answers 409.
 *
 * <p>Rule: UC-21, BR-35, BR-36, BR-37, BR-38, BR-39; D-97; D-111 E2; D-117; RF-33; MSG21, MSG23.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PositionServiceImpl implements PositionService {

    private static final Logger log = LoggerFactory.getLogger(PositionServiceImpl.class);

    private final TradingPlanRepository plans;
    private final TradeJournal journal;
    private final MarketHealth health;
    private final TradingPlanService tradingPlans;
    private final Clock clock;
    /** The demo account a plan's position is held on, closed through paper trading. */
    private final ObjectProvider<PlanOrderPlacer> orderPlacer;

    @Override
    @Transactional(readOnly = true)
    public CloseEstimateResponse closeEstimate(UUID userId, UUID planId) {
        OpenTrade trade = openTrade(userId, planId);
        PlanOrderPlacer.PlanExitQuote quote = orderPlacer.getObject().quoteClose(userId, trade);
        BigDecimal move = quote.exitPrice().subtract(trade.entryPrice());
        BigDecimal priceResult = Rounding.toAmount(
                (trade.direction() == Direction.LONG ? move : move.negate()).multiply(quote.quantity()));
        BigDecimal entryFee = trade.entryFee() == null ? BigDecimal.ZERO : trade.entryFee();
        BigDecimal net = priceResult.subtract(entryFee).subtract(quote.exitFee());
        if (quote.fundingSoFar() != null) {
            net = net.add(quote.fundingSoFar());
        }
        return new CloseEstimateResponse(
                quote.currentPrice(),
                quote.exitPrice(),
                priceResult,
                entryFee,
                quote.exitFee(),
                quote.fundingSoFar(),
                false,
                Rounding.toAmount(net),
                clock.instant());
    }

    @Override
    @Transactional
    public TradingPlanResponse closePosition(UUID userId, UUID planId) {
        OpenTrade trade = openTrade(userId, planId);
        health.requireTrading(trade.market());
        orderPlacer.getObject().close(userId, trade);
        log.info("UC-21 position {} of plan {} closed by its Trader on the demo account", trade.positionId(), planId);
        return tradingPlans.get(userId, planId);
    }

    /**
     * The caller's plan's OPEN position on the demo account: 404 when the plan is not the caller's, 409 when it has
     * none, or only a record of the former engine.
     */
    private OpenTrade openTrade(UUID userId, UUID planId) {
        TradingPlan plan = plans.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("TradingPlan", planId));
        boolean onDemoAccount =
                journal.positionOf(planId).map(RecordedPosition::onDemoAccount).orElse(false);
        return journal.openSimulatedTrades(userId).stream()
                .filter(trade -> trade.planId().equals(planId))
                .filter(trade -> onDemoAccount)
                .findFirst()
                .orElseThrow(() ->
                        new IllegalPlanStateException(planId, plan.getStatus(), "close a position that is not OPEN"));
    }
}
