package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.entity.PaperOrderList;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TriggerCondition;
import com.cryptopilot.paper.model.enums.WorkingType;
import com.cryptopilot.paper.repository.PaperOrderListRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Futures opening order with its take profit and stop loss, as the app's TP/SL box places them (TR-20, plan 5.6): an
 * OTOCO group of the opening order and two pending conditional orders that close the whole position, a
 * TAKE_PROFIT_MARKET and a STOP_MARKET.
 *
 * <ul>
 *   <li>Once the opening order has ended having executed something, the two are placed and watched: each from then
 *       on, never on a price before (D-77). One whose condition is already met then is rejected
 *       ({@code WOULD_TRIGGER}), as the exchange refuses a conditional order that would trigger at once.
 *   <li>Ended having executed nothing, it takes the two with it.
 *   <li>Once one of the two ends, triggered or not, the other is cancelled: one cancels the other.
 * </ul>
 *
 * <p>Runs inside the caller's transaction, which holds the Trader's lock.
 *
 * <p>Rule: TR-20; D-77.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class FuturesOrderLists {

    /** Why a take profit or stop loss was not placed: its condition was already met when its order executed. */
    static final String WOULD_TRIGGER = "WOULD_TRIGGER";

    private final PaperOrderRepository orders;
    private final PaperOrderListRepository lists;
    private final FuturesMargins margins;
    private final PaperUserFeed feed;
    private final MarketApi market;
    private final ApplicationEventPublisher events;

    /**
     * Groups a stored opening order, not executed yet, with its take profit and stop loss, pending.
     *
     * @param takeProfit the take profit's trigger, or {@code null}
     * @param stopLoss the stop loss's trigger, or {@code null}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void attach(PaperOrder opening, BigDecimal takeProfit, BigDecimal stopLoss, WorkingType workingType) {
        PaperOrderList list = lists.save(PaperOrderList.futuresTpSl(opening.getUserId(), opening.getPairId(), null));
        opening.inList(list.getId(), false);
        OrderSide closing = opening.getSide() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
        if (takeProfit != null) {
            orders.save(leg(opening, OrderType.TAKE_PROFIT_MARKET, closing, takeProfit, workingType)
                    .inList(list.getId(), true));
        }
        if (stopLoss != null) {
            orders.save(leg(opening, OrderType.STOP_MARKET, closing, stopLoss, workingType)
                    .inList(list.getId(), true));
        }
    }

    /**
     * After an order of a group may have ended: the opening order places or takes its two; one of the two cancels the
     * other. Nothing for an order of no group, or one that still works.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void settle(PaperAccount account, PaperOrder order, Instant at) {
        if (order.getListId() == null || order.isOpen() || order.isPending()) {
            return;
        }
        Optional<PaperOrderList> found = lists.findById(order.getListId());
        if (found.isEmpty() || !found.get().isExecuting()) {
            return;
        }
        PaperOrderList list = found.get();
        List<PaperOrder> legs = orders.findByListIdOrderByClientOrderIdAsc(list.getId()).stream()
                .filter(leg -> !leg.getId().equals(order.getId()))
                .toList();
        if (!order.isConditional()) {
            boolean executed = order.getExecutedQuantity().signum() > 0;
            for (PaperOrder leg : legs) {
                if (!leg.isPending()) {
                    continue;
                }
                if (executed) {
                    place(account, leg, at);
                } else {
                    leg.cancel(at);
                }
            }
            if (!executed) {
                list.done();
            }
            return;
        }
        for (PaperOrder leg : legs) {
            if (leg.isConditional() && (leg.isOpen() || leg.isPending())) {
                leg.cancel(at);
                feed.futuresOrder(account, leg);
                events.publishEvent(new PaperOrderCanceled(leg.getId()));
            }
        }
        list.done();
    }

    /** Places a pending take profit or stop loss now, or rejects it when its condition is already met. */
    private void place(PaperAccount account, PaperOrder leg, Instant at) {
        Optional<BigDecimal> latest = leg.getWorkingType() == WorkingType.MARK_PRICE
                ? margins.freshMark(symbolOf(leg))
                : market.currentLastPrice(MarketType.FUTURES, symbolOf(leg));
        if (latest.isPresent() && met(leg, latest.get())) {
            leg.reject(WOULD_TRIGGER, at);
            feed.futuresOrder(account, leg);
            return;
        }
        leg.activate(at);
        events.publishEvent(FuturesTrigger.awaiting(leg));
    }

    private static boolean met(PaperOrder leg, BigDecimal price) {
        int side = price.compareTo(leg.getStopPrice());
        return leg.getTriggerCondition() == TriggerCondition.AT_OR_ABOVE ? side >= 0 : side <= 0;
    }

    private static PaperOrder leg(
            PaperOrder opening, OrderType type, OrderSide side, BigDecimal trigger, WorkingType workingType) {
        return PaperOrder.futuresConditional(
                opening.getUserId(),
                opening.getPairId(),
                UuidV7.next().toString(),
                type,
                side,
                opening.getPositionSide(),
                null,
                trigger,
                null,
                null,
                workingType,
                false,
                false,
                true);
    }

    // fk_trade_order_pair keeps the pair of an order stored.
    private String symbolOf(PaperOrder order) {
        return market.pairListings(List.of(order.getPairId())).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("pair " + order.getPairId() + " is not stored"))
                .symbol();
    }
}
