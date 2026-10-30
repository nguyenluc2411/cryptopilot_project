package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.calculator.FuturesMarginMath;
import com.cryptopilot.paper.config.PaperFuturesProperties;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.entity.FuturesSymbolConfig;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.event.PaperOrderAwaitingMarkTrigger;
import com.cryptopilot.paper.event.PaperOrderAwaitingTrigger;
import com.cryptopilot.paper.model.FuturesAccountMargin;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.TriggerOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.PositionDirection;
import com.cryptopilot.paper.model.enums.WorkingType;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import com.cryptopilot.paper.service.impl.FuturesMargins.FuturesPair;
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
 * What a Futures conditional order does when the price triggers it (TR-20, plan 5.4), in the caller's transaction,
 * which holds the Trader's lock and found the order still waiting:
 *
 * <ol>
 *   <li>With {@code priceProtect}, while the mark and the last price are further apart than {@code trigger-protect}
 *       of the mark, it does not trigger and is watched again for the next price.
 *   <li>A {@code closePosition} order closes the whole position as it is now, a reduce-only one at most that; with no
 *       position to close it expires.
 *   <li>The part that would open or increase a position is checked now, as the exchange checks margin when a
 *       conditional order triggers rather than when it is placed: against the available balance (cross) or the cross
 *       wallet's free cash (isolated), and the leverage's largest notional; on a {@code MARK_STALE} pair it is not
 *       taken. Refused, the conditional order is REJECTED with the reason.
 *   <li>Otherwise it places the order it carries, a MARKET or a LIMIT order pointing back at it, ends FILLED (the
 *       exchange's TRIGGERED) and the placed order meets the book as any order placed now ({@link FuturesArrival}).
 * </ol>
 *
 * <p>Rule: TR-20; D-127.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class FuturesTrigger {

    /** Why a conditional order ended: it placed the order it carries (the exchange's TRIGGERED). */
    static final String TRIGGERED = "TRIGGERED";

    /** Why a closing conditional order expired: there was no position for it to close. */
    static final String NOTHING_TO_CLOSE = "NOTHING_TO_CLOSE";

    /** Why a conditional order was rejected: the margin it needed was not there when it triggered. */
    static final String MARGIN_INSUFFICIENT = "MARGIN_INSUFFICIENT";

    /** Why a conditional order was rejected: the position would pass the leverage's largest notional. */
    static final String MAX_POSITION = "MAX_POSITION_AT_LEVERAGE";

    /** Why a conditional order was rejected: its pair's mark price was stale, so no risk could be opened. */
    static final String MARK_STALE = "MARK_STALE";

    /** Why an opening order was not placed at its trigger: its price, or the book's, is past PERCENT_PRICE (TR-39). */
    static final String PERCENT_PRICE = "PERCENT_PRICE";

    private final PaperOrderRepository orders;
    private final FuturesMargins margins;
    private final FuturesArrival arrival;
    private final PaperUserFeed feed;
    private final FuturesOrderLists tpSl;
    private final MarketApi market;
    private final PaperFuturesProperties futures;
    private final ApplicationEventPublisher events;

    @Transactional(propagation = Propagation.MANDATORY)
    void execute(PaperAccount account, PaperOrder conditional, Instant at, FillSource source) {
        FuturesPair pair = margins.pair(symbolOf(conditional));
        // Read once: the mark decides the protection, the book, the stale-mark refusal and PERCENT_PRICE alike.
        Optional<BigDecimal> freshMark = margins.freshMark(pair.symbol());
        if (conditional.isPriceProtect() && !withinProtection(pair.symbol(), freshMark)) {
            events.publishEvent(awaiting(conditional));
            return;
        }
        // The price the placed order takes is the one PERCENT_PRICE judges.
        Optional<SpotArrival.Book> book =
                arrival.bookFor(pair.pairId(), pair.symbol(), conditional.getSide(), freshMark.isPresent());
        if (conditional.getOrderType().isFuturesMarketConditional() && book.isEmpty()) {
            // No price to fill at now, a stale pair's book as old as its mark among them (8.2.1 rule 2): it is not
            // spent on an order that would expire unfilled, but waits for the next price.
            events.publishEvent(awaiting(conditional));
            return;
        }
        conditional.trigger(at);
        FuturesPosition position = margins.openPosition(account.userId(), pair.pairId(), conditional.getPositionSide());
        BigDecimal size = position == null ? BigDecimal.ZERO : position.signedQuantity();
        BigDecimal closable = FuturesSettlement.closableBy(conditional.getSide(), size);
        boolean reducing = conditional.reducing() || conditional.isClosePosition();
        BigDecimal quantity = conditional.isClosePosition() ? closable : conditional.getOrigQuantity();
        if (reducing) {
            quantity = quantity.min(closable);
            if (quantity.signum() == 0) {
                end(account, conditional, OrderStatus.EXPIRED, NOTHING_TO_CLOSE, at);
                return;
            }
        }
        BigDecimal opening =
                reducing ? BigDecimal.ZERO : FuturesOrderServiceImpl.opening(conditional.getSide(), quantity, size);
        if (opening.signum() > 0) {
            Optional<String> refused = refusal(account, conditional, pair, size, opening, book, freshMark);
            if (refused.isPresent()) {
                end(account, conditional, OrderStatus.REJECTED, refused.get(), at);
                return;
            }
        }
        PaperOrder placed =
                orders.save(placedOrder(conditional, quantity, reducing).placedBy(conditional));
        end(account, conditional, OrderStatus.FILLED, TRIGGERED, at);
        if (conditional.getOrderType().isFuturesMarketConditional()) {
            arrival.market(account, placed, book, at);
        } else {
            arrival.limit(account, placed, book, at);
        }
        feed.futuresOrder(account, placed);
    }

    /**
     * What the engine watches the order as, on the price it triggers on: the last price, or the pair's mark prices
     * (TR-20). Published inside the placing transaction; the engine reads it after the commit.
     */
    static Object awaiting(PaperOrder conditional) {
        TriggerOrder watched = new TriggerOrder(
                conditional.getId(),
                conditional.getUserId(),
                MarketType.FUTURES,
                conditional.getPairId(),
                conditional.getSide(),
                conditional.getTriggerCondition(),
                conditional.getStopPrice(),
                conditional.getCallbackRate(),
                conditional.getTrailingExtremePrice(),
                conditional.watchedSince());
        return conditional.getWorkingType() == WorkingType.MARK_PRICE
                ? new PaperOrderAwaitingMarkTrigger(watched)
                : new PaperOrderAwaitingTrigger(watched);
    }

    /**
     * Why the opening part cannot be taken now, if it cannot: a stale mark, a price past PERCENT_PRICE as a placed order
     * would be refused (plan 8.2), too large a position, too little margin.
     */
    private Optional<String> refusal(
            PaperAccount account,
            PaperOrder conditional,
            FuturesPair pair,
            BigDecimal size,
            BigDecimal opening,
            Optional<SpotArrival.Book> book,
            Optional<BigDecimal> mark) {
        if (mark.isEmpty()) {
            return Optional.of(MARK_STALE);
        }
        BigDecimal judged = conditional.getLimitPrice() != null
                ? conditional.getLimitPrice()
                : book.map(found -> found.levels().getFirst().price()).orElse(null);
        if (!FuturesOrderServiceImpl.withinPercentPrice(pair.filters(), conditional.getSide(), judged, mark.get())) {
            return Optional.of(PERCENT_PRICE);
        }
        BigDecimal last =
                market.currentLastPrice(MarketType.FUTURES, pair.symbol()).orElse(mark.get());
        FuturesSymbolConfig config = margins.symbolConfig(account.userId(), pair.pairId(), pair.tiers());
        int leverage = config.getLeverage();
        BigDecimal limit = conditional.getLimitPrice();
        BigDecimal held =
                PositionDirection.openedBy(conditional.getSide()).sign().signum() == size.signum()
                        ? size.abs()
                        : BigDecimal.ZERO;
        BigDecimal notional = held.add(opening).multiply(limit != null ? limit : mark.get());
        if (notional.compareTo(FuturesMarginMath.maxNotionalAtLeverage(pair.tiers(), leverage)) > 0) {
            return Optional.of(MAX_POSITION);
        }
        BigDecimal cost =
                FuturesMarginMath.orderCost(conditional.getSide(), opening, limit, last, mark.get(), leverage);
        FuturesAccountMargin margin =
                margins.crossState(account.userId(), conditional.getId(), true).margin();
        BigDecimal budget =
                config.getMarginType() == MarginType.CROSSED ? margin.availableBalance() : margin.crossFreeCash();
        return cost.compareTo(budget) > 0 ? Optional.of(MARGIN_INSUFFICIENT) : Optional.empty();
    }

    /** Whether the mark and the last price are close enough for a protected order to trigger. */
    private boolean withinProtection(String symbol, Optional<BigDecimal> mark) {
        Optional<BigDecimal> last = market.currentLastPrice(MarketType.FUTURES, symbol);
        if (mark.isEmpty() || last.isEmpty()) {
            return false;
        }
        BigDecimal gap = Rounding.divide(mark.get().subtract(last.get()).abs(), mark.get());
        return gap.compareTo(futures.triggerProtect()) <= 0;
    }

    /** The order the conditional order carries, of the quantity it acts on now. */
    private static PaperOrder placedOrder(PaperOrder conditional, BigDecimal quantity, boolean reducing) {
        String key = UuidV7.next().toString();
        return conditional.getOrderType().isFuturesMarketConditional()
                ? PaperOrder.futuresMarket(
                        conditional.getUserId(),
                        conditional.getPairId(),
                        key,
                        conditional.getSide(),
                        conditional.getPositionSide(),
                        quantity,
                        reducing)
                : PaperOrder.futuresLimit(
                        conditional.getUserId(),
                        conditional.getPairId(),
                        key,
                        conditional.getSide(),
                        conditional.getPositionSide(),
                        conditional.getTimeInForce(),
                        conditional.getLimitPrice(),
                        quantity,
                        reducing);
    }

    private void end(PaperAccount account, PaperOrder conditional, OrderStatus status, String reason, Instant at) {
        conditional.endTrigger(status, reason, at);
        feed.futuresOrder(account, conditional);
        tpSl.settle(account, conditional, at);
    }

    // fk_trade_order_pair keeps the pair of an order stored.
    private String symbolOf(PaperOrder order) {
        return market.pairListings(List.of(order.getPairId())).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("pair " + order.getPairId() + " is not stored"))
                .symbol();
    }
}
