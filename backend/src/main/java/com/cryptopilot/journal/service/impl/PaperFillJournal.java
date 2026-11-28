package com.cryptopilot.journal.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.journal.entity.TradingJournal;
import com.cryptopilot.journal.repository.TradingJournalRepository;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.event.PaperFuturesPositionFilled;
import com.cryptopilot.paper.event.PaperSpotFilled;
import com.cryptopilot.trading.PlanOrderTerms;
import com.cryptopilot.trading.TradingPlanApi;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The journal's side of paper trading (RF-35): every paper fill is recorded in the fill's own transaction, so a fill
 * and its record commit or roll back together.
 *
 * <ul>
 *   <li>Spot: a buy order opens one LONG record, its later fills add to it at their average price. A sale closes the
 *       Trader's open Spot records of the pair, oldest first, each by the part of the sale it takes; a record sold out
 *       closes at the average of its sales.
 *   <li>Futures: one record per position, opened by its first fill and following its entry and largest quantity;
 *       closed with the position's own average close price, realised PnL, fees and funding.
 * </ul>
 *
 * <p>A record whose order carries a plan is SIMULATED with the plan's levels; any other is DEMO_ORDER without levels
 * (no stop loss). A plan has one record: a second order of it records as a demo order. A sale or close of what was
 * bought before the journal followed paper trading (V77) finds no record and changes nothing.
 *
 * <p>Rule: BM04 F-06, F-07; BR-35 to BR-38; D-97, D-117; RF-33, RF-35.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperFillJournal {

    private static final Logger log = LoggerFactory.getLogger(PaperFillJournal.class);

    private final TradingJournalRepository records;
    private final TradingPlanApi plans;
    private final Clock clock;

    /** A Spot fill: a buy opens or adds to its order's record; a sale closes open records, oldest first. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(PaperSpotFilled fill) {
        if (fill.buy()) {
            bought(fill);
        } else {
            sold(fill);
        }
    }

    /** A Futures fill: the position's record is opened, follows the position, and closes with it. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(PaperFuturesPositionFilled fill) {
        Optional<TradingJournal> found = records.findByPaperPositionId(fill.positionId());
        UUID id;
        if (found.isPresent()) {
            id = found.get().getId();
        } else if (fill.opened()) {
            Optional<PlanOrderTerms> plan = planOf(fill.orderPlanId());
            TradingJournal record = records.saveAndFlush(TradingJournal.openFromPaper(
                    fill.userId(),
                    fill.pairId(),
                    plan.map(PlanOrderTerms::planId).orElse(null),
                    MarketType.FUTURES,
                    fill.longPosition() ? Direction.LONG : Direction.SHORT,
                    fill.leverage(),
                    fill.entryPrice(),
                    fill.maxQuantity(),
                    fill.openedAt() == null ? fill.at() : fill.openedAt(),
                    fill.maker() ? LiquidityRole.MAKER : LiquidityRole.TAKER,
                    Rounding.toAmount(fill.feeAmount()),
                    plan.map(PlanOrderTerms::stopLoss).orElse(null),
                    plan.map(PlanOrderTerms::takeProfit).orElse(null),
                    null,
                    fill.positionId()));
            id = record.getId();
            log.info("RF-35 journal {} opened for Futures position {} ({})", id, fill.positionId(), record.getSource());
        } else {
            return;
        }
        if (fill.open()) {
            records.followPosition(id, fill.maxQuantity(), fill.entryPrice(), fill.leverage(), clock.instant());
            return;
        }
        records.closePosition(
                id,
                fill.maxQuantity(),
                fill.entryPrice(),
                fill.closeAvgPrice() == null ? fill.price() : fill.closeAvgPrice(),
                fill.at(),
                futuresReason(fill).name(),
                Rounding.toAmount(fill.realizedPnl()),
                Rounding.toAmount(fill.feeAmount()),
                fill.fundingAmount() == null ? null : Rounding.toAmount(fill.fundingAmount()),
                clock.instant());
        log.info("RF-35 journal {} closed with Futures position {}", id, fill.positionId());
    }

    private void bought(PaperSpotFilled fill) {
        Optional<TradingJournal> found = records.findByPaperOrderId(fill.orderId());
        if (found.isPresent()) {
            records.addToEntry(found.get().getId(), fill.price(), fill.baseHeld(), fill.feeInQuote(), clock.instant());
            return;
        }
        Optional<PlanOrderTerms> plan = planOf(fill.planId());
        TradingJournal record = records.saveAndFlush(TradingJournal.openFromPaper(
                fill.userId(),
                fill.pairId(),
                plan.map(PlanOrderTerms::planId).orElse(null),
                MarketType.SPOT,
                Direction.LONG,
                null,
                fill.price(),
                // The coin the buy left: its fee was taken in it (TR-13), so a sale of all of it closes the record.
                fill.baseHeld(),
                fill.at(),
                fill.maker() ? LiquidityRole.MAKER : LiquidityRole.TAKER,
                fill.feeInQuote(),
                plan.map(PlanOrderTerms::stopLoss).orElse(null),
                plan.map(PlanOrderTerms::takeProfit).orElse(null),
                fill.orderId(),
                null));
        log.info("RF-35 journal {} opened by Spot order {} ({})", record.getId(), fill.orderId(), record.getSource());
    }

    private void sold(PaperSpotFilled fill) {
        BigDecimal left = fill.quantity();
        String reason = spotReason(fill.orderType()).name();
        for (TradingJournal record : records.findOpenPaperSpot(fill.userId(), fill.pairId())) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal closed = record.getExitQuantity() == null ? BigDecimal.ZERO : record.getExitQuantity();
            BigDecimal open = record.getQuantity().subtract(closed);
            BigDecimal taken = open.min(left);
            if (taken.signum() <= 0) {
                continue;
            }
            BigDecimal share = taken.divide(fill.quantity(), MathContext.DECIMAL64);
            BigDecimal realized = fill.price().subtract(record.getEntryPrice()).multiply(taken);
            Instant now = clock.instant();
            records.addExit(
                    record.getId(),
                    taken,
                    fill.price().multiply(taken),
                    Rounding.toAmount(realized),
                    Rounding.toAmount(fill.feeInQuote().multiply(share)),
                    now);
            if (taken.compareTo(open) == 0 && records.closeSoldOut(record.getId(), fill.at(), reason, now) == 1) {
                log.info("RF-35 journal {} closed by Spot order {} ({})", record.getId(), fill.orderId(), reason);
            }
            left = left.subtract(taken);
        }
    }

    /** The plan of the order, when it has none recorded yet: a plan results in one trade. */
    private Optional<PlanOrderTerms> planOf(UUID planId) {
        if (planId == null || records.existsByPlanId(planId)) {
            return Optional.empty();
        }
        return plans.terms(planId);
    }

    /** A Spot sale by a stop is a stop loss, by a take profit (an OCO's limit maker included) a take profit. */
    static CloseReason spotReason(String orderType) {
        if (orderType.startsWith("STOP_LOSS")) {
            return CloseReason.STOP_LOSS;
        }
        if (orderType.startsWith("TAKE_PROFIT") || orderType.equals("LIMIT_MAKER")) {
            return CloseReason.TAKE_PROFIT;
        }
        return CloseReason.MANUAL;
    }

    /** A liquidated position is LIQUIDATION; a close by a take profit or stop order names it; any other is MANUAL. */
    static CloseReason futuresReason(PaperFuturesPositionFilled fill) {
        if ("LIQUIDATION".equals(fill.closeReason())) {
            return CloseReason.LIQUIDATION;
        }
        if (fill.orderType().startsWith("TAKE_PROFIT")) {
            return CloseReason.TAKE_PROFIT;
        }
        if (fill.orderType().startsWith("STOP")) {
            return CloseReason.STOP_LOSS;
        }
        return CloseReason.MANUAL;
    }
}
