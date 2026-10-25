package com.cryptopilot.trading.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.dto.response.CloseEstimateResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.exception.IllegalPlanStateException;
import com.cryptopilot.trading.model.ClosingAmounts;
import com.cryptopilot.trading.model.Excursion;
import com.cryptopilot.trading.model.Exit;
import com.cryptopilot.trading.model.NetPnl;
import com.cryptopilot.trading.model.PositionExit;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.TradeAmounts;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.PositionService;
import com.cryptopilot.trading.service.TradingPlanService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The estimate and the manual close of an open position, both priced by {@link PositionCloser#preview}: the current
 * last price (never the mark price) moved by {@code SLIPPAGE_BPS}, the taker exit fee, the stored entry fee and the
 * funding up to now, with the net of {@link TradeAmounts#netPnl}. The close is the engine's compare-and-set on the
 * journal row, so the estimate equals the stored close when the price has not moved, and a close racing an exit of the
 * engine lets exactly one through; the loser answers 409.
 *
 * <p>Rule: UC-21, BR-35, BR-36, BR-37, BR-38, BR-39; D-97; D-111 E2, E5; MSG21, MSG23.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PositionServiceImpl implements PositionService {

    private static final Logger log = LoggerFactory.getLogger(PositionServiceImpl.class);

    private final TradingPlanRepository plans;
    private final TradeJournal journal;
    private final PositionCloser closer;
    private final MarketApi market;
    private final TradingPlanService tradingPlans;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public CloseEstimateResponse closeEstimate(UUID userId, UUID planId) {
        OpenTrade trade = openTrade(userId, planId);
        BigDecimal currentPrice = currentLastPrice(trade);
        ClosingAmounts closing = closer.preview(trade, CloseReason.MANUAL, currentPrice, clock.instant());
        TradeAmounts amounts = closing.amounts();
        NetPnl net = amounts.netPnl();
        return new CloseEstimateResponse(
                currentPrice,
                closing.exitPrice(),
                amounts.realizedPnl(),
                trade.entryFee(),
                closing.exitFee(),
                amounts.fundingFeeAmount(),
                amounts.fundingIncomplete(),
                net.amount(),
                closing.exitTime());
    }

    @Override
    @Transactional
    public TradingPlanResponse closePosition(UUID userId, UUID planId) {
        OpenTrade trade = openTrade(userId, planId);
        BigDecimal currentPrice = currentLastPrice(trade);
        Instant now = clock.instant();
        boolean closed = closer.close(new PositionExit(
                TrackedPosition.of(trade),
                new Exit(planId, CloseReason.MANUAL, currentPrice, now),
                Excursion.at(trade.entryPrice())));
        if (!closed) {
            // The engine's exit or another close committed between the read and the compare-and-set.
            throw new IllegalPlanStateException(planId, PlanStatus.EXECUTED, "close a position closed first");
        }
        log.info("UC-21 position {} of plan {} closed by its Trader at {}", trade.positionId(), planId, currentPrice);
        return tradingPlans.get(userId, planId);
    }

    /** The caller's plan's OPEN position: 404 when the plan is not the caller's, 409 when it has none. */
    private OpenTrade openTrade(UUID userId, UUID planId) {
        TradingPlan plan = plans.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("TradingPlan", planId));
        return journal.openTradeOfPlan(planId)
                .orElseThrow(() ->
                        new IllegalPlanStateException(planId, plan.getStatus(), "close a position that is not OPEN"));
    }

    /** The pair's current last price, the price a manual close executes at (D-111 E2); 503 when none is cached. */
    private BigDecimal currentLastPrice(OpenTrade trade) {
        return market.tradablePair(trade.pairId(), trade.market())
                .flatMap(pair -> market.currentLastPrice(trade.market(), pair.symbol()))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.MARKET_PRICE_UNAVAILABLE,
                        "no current last price for pair " + trade.pairId() + " on " + trade.market()
                                + " to close position " + trade.positionId()));
    }
}
