package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.config.PaperOrderProperties;
import com.cryptopilot.paper.dto.request.PlaceOrderListRequest;
import com.cryptopilot.paper.dto.request.PlaceOrderListRequest.Leg;
import com.cryptopilot.paper.dto.request.PlaceOrderListRequest.Oco;
import com.cryptopilot.paper.dto.response.OrderListResponse;
import com.cryptopilot.paper.entity.PaperAccount;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.entity.PaperOrderList;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.matching.BookSweep;
import com.cryptopilot.paper.model.PlacedOrderList;
import com.cryptopilot.paper.model.enums.OrderListType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TimeInForce;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.PaperOrderListRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The groups of Spot orders placed together (TR-10): OCO, OTO and OTOCO.
 *
 * <h2>Roles</h2>
 *
 * <p>The orders are those of the exchange's Spot order lists: the take profit of an OCO is a LIMIT_MAKER and its stop a
 * STOP_LOSS_LIMIT, or a STOP_LOSS without a limit price; the working order of an OTO or OTOCO is a LIMIT or a
 * LIMIT_MAKER; the pending order of an OTO any of LIMIT, LIMIT_MAKER, STOP_LOSS, STOP_LOSS_LIMIT, TAKE_PROFIT and
 * TAKE_PROFIT_LIMIT.
 *
 * <p>Each order of a group has a role, written as the suffix of its client order id after the group's id: the working
 * order of an OTO or OTOCO ({@code :w}), the pending order of an OTO ({@code :p}), and the take profit ({@code :tp})
 * and stop ({@code :sl}) of an OCO, alone or as the pending part of an OTOCO.
 *
 * <h2>One cancels the other</h2>
 *
 * <p>The two orders of an OCO sell or buy the same quantity, so they lock once: the larger of what each needs, as the
 * exchange locks an OCO once. The moment one of them executes or triggers, the other expires
 * ({@code OCO_OTHER_EXECUTED}) and what it needed beyond the one that goes on is given back; the two never execute
 * both.
 *
 * <h2>One triggers the other</h2>
 *
 * <p>The pending order of an OTO, or the pending OCO of an OTOCO, locks nothing while it waits. Its stop is checked
 * against the working order's price, where it starts: one already met there is refused, and the OCO's take profit and
 * stop lie on either side of it. Once the working order has filled in full, it is placed: it locks what it needs and
 * meets the market as an order placed then does. When too little is free by then — the fee of a buy is taken in the
 * coin bought — it is REJECTED ({@code INSUFFICIENT_BALANCE}) and the working order's fill stands. A stop the last
 * price already meets by then ends EXPIRED ({@code WOULD_TRIGGER_ON_ACTIVATION}), locking nothing, and the fill stands
 * too. An OTOCO that gives no OCO quantity gets the one its working order leaves, net of the fee, so its OCO can be
 * placed.
 *
 * <p>A group placed with a {@code listClientOrderId} is placed once: the key is looked up under the Trader's lock
 * before anything is stored. Every order of a group is checked against the pair's Spot filters
 * ({@link SpotFilters}) and counts towards the open orders allowed on the pair ({@link SpotOrderLimits}).
 *
 * <p>Cancelling any order of a group cancels the group: what its working orders locked is given back, once for an
 * OCO. A group is ALL_DONE once none of its orders works or waits.
 *
 * <p>Runs inside the caller's transaction, which holds the Trader's paper lock.
 *
 * <p>Rule: TR-10; BR-23, BR-30.
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 (contingent orders: one-cancels-other and one-triggers-other).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperOrderLists {

    static final String WORKING = ":w";
    static final String PENDING = ":p";
    static final String TAKE_PROFIT = ":tp";
    static final String STOP = ":sl";

    /** Why the other order of an OCO ended: one of the two executed or triggered. */
    static final String OTHER_EXECUTED = "OCO_OTHER_EXECUTED";

    /** Why a pending order could not be placed when its working order filled. */
    static final String INSUFFICIENT = "INSUFFICIENT_BALANCE";

    /** Why a pending stop ended when its working order filled: the last price already met it. */
    static final String WOULD_TRIGGER_ON_ACTIVATION = "WOULD_TRIGGER_ON_ACTIVATION";

    private final PaperOrderRepository orders;
    private final PaperOrderListRepository lists;
    private final PaperWallets wallets;
    private final SpotArrival arrival;
    private final PaperUserFeed feed;
    private final ApplicationEventPublisher events;
    private final MarketApi market;
    private final QuoteUsdtRate usdtRates;
    private final PaperOrderProperties fees;
    private final SpotOrderLimits limits;
    private final Clock clock;

    /**
     * Places a group: see the class comment and {@link PlaceOrderListRequest}. A refusal stores nothing. A
     * {@code listClientOrderId} already used returns that group when it was placed with the same values, and is a
     * {@code DATA_CONFLICT} otherwise, as a {@code clientOrderId} is for one order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PlacedOrderList place(PaperAccount account, PlaceOrderListRequest request) {
        requireShape(request);
        // A pair the exchange stopped trading takes no new order: its open ones are cancelled (NSF-01).
        TradablePair pair = market.tradablePair(request.pairId(), request.market())
                .filter(TradablePair::exchangeTrading)
                .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", request.pairId()));
        if (request.listClientOrderId() != null) {
            // Under the lock, so a retry that raced the original waited for it and finds it here.
            Optional<PaperOrderList> placed =
                    lists.findByAccountIdAndListClientOrderId(account.getId(), request.listClientOrderId());
            if (placed.isPresent()) {
                return new PlacedOrderList(replayOf(placed.get(), request), false);
            }
        }
        PairCoins coins = market.pairCoins(pair.pairId())
                .orElseThrow(() -> new IllegalStateException("pair " + pair.pairId() + " is tradable but not stored"));
        usdtRates.requireValued(coins);
        PairFilters filters = pair.filters();
        BigDecimal last = market.currentLastPrice(MarketType.SPOT, pair.symbol())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.MARKET_PRICE_UNAVAILABLE, "no current last price for " + pair.symbol() + " on SPOT"));
        limits.requireRoom(account.getId(), pair.pairId(), filters, ordersOf(request), stopsOf(request));
        PaperOrderList list = lists.save(
                PaperOrderList.spot(account.getId(), pair.pairId(), request.type(), request.listClientOrderId()));
        Quote quote = new Quote(pair, filters, last);
        String key = list.getId().toString();
        Instant now = clock.instant();
        switch (request.type()) {
            case OCO -> {
                Oco oco = request.oco();
                requireOcoAround(oco, last, "the last price");
                PaperOrder takeProfit = takeProfit(account, quote, key, oco, oco.quantity(), false);
                PaperOrder stop = stop(account, quote, key, oco, oco.quantity(), false);
                startOco(account, pair, coins, takeProfit, stop, now);
            }
            case OTO -> {
                PaperOrder working = working(account, quote, key, request.working());
                pending(account, quote, key, request.pending(), working.getLimitPrice());
                startWorking(account, pair, coins, working, now);
            }
            case OTOCO -> {
                PaperOrder working = working(account, quote, key, request.working());
                requireOcoAround(request.oco(), working.getLimitPrice(), "the working order's price");
                BigDecimal quantity = request.oco().quantity() != null
                        ? request.oco().quantity()
                        : ocoQuantityAfter(working, filters);
                takeProfit(account, quote, key, request.oco(), quantity, true);
                stop(account, quote, key, request.oco(), quantity, true);
                startWorking(account, pair, coins, working, now);
            }
        }
        refresh(list);
        return new PlacedOrderList(response(list), true);
    }

    /** How many orders a group adds to the account's open orders on the pair, its pending ones included. */
    private static int ordersOf(PlaceOrderListRequest request) {
        return request.type() == OrderListType.OTOCO ? 3 : 2;
    }

    /** How many of them are stops: an OCO's stop, or an OTO's pending order when it is one. */
    private static int stopsOf(PlaceOrderListRequest request) {
        return request.type() != OrderListType.OTO || request.pending().type().isSpotStop() ? 1 : 0;
    }

    /**
     * The group a retry asks for again. A key reused for a different group is a client error, refused rather than
     * answered with a group the client did not ask for.
     */
    private OrderListResponse replayOf(PaperOrderList placed, PlaceOrderListRequest request) {
        Map<String, PaperOrder> byRole = byRole(legs(placed.getId()));
        boolean same = placed.getPairId().equals(request.pairId())
                && placed.getMarketType() == request.market()
                && placed.getListType() == request.type()
                && sameLeg(byRole.get(WORKING), request.working())
                && sameLeg(byRole.get(PENDING), request.pending())
                && sameOco(byRole.get(TAKE_PROFIT), byRole.get(STOP), request.oco());
        if (!same) {
            throw new BusinessException(
                    ErrorCode.DATA_CONFLICT,
                    "listClientOrderId " + request.listClientOrderId() + " was used for group " + placed.getId()
                            + " of other values");
        }
        return response(placed);
    }

    private static boolean sameLeg(PaperOrder stored, Leg asked) {
        if (asked == null || stored == null) {
            return asked == null && stored == null;
        }
        return stored.getSide() == asked.side()
                && stored.getOrderType() == asked.type()
                && sameValue(stored.getLimitPrice(), asked.price())
                && sameValue(stored.getStopPrice(), asked.stopPrice())
                && sameValue(stored.getOrigQuantity(), asked.quantity());
    }

    /** An OTOCO's OCO sent without a quantity took its working order's, which the working leg already compares. */
    private static boolean sameOco(PaperOrder takeProfit, PaperOrder stop, Oco asked) {
        if (asked == null || takeProfit == null || stop == null) {
            return asked == null && takeProfit == null && stop == null;
        }
        return takeProfit.getSide() == asked.side()
                && sameValue(takeProfit.getLimitPrice(), asked.takeProfitPrice())
                && sameValue(stop.getStopPrice(), asked.stopPrice())
                && sameValue(stop.getLimitPrice(), asked.stopLimitPrice())
                && (asked.quantity() == null || sameValue(takeProfit.getOrigQuantity(), asked.quantity()));
    }

    private static boolean sameValue(BigDecimal stored, BigDecimal asked) {
        return stored == null ? asked == null : asked != null && stored.compareTo(asked) == 0;
    }

    /**
     * Before an order of a group executes or triggers: the other order of its OCO expires, and what it needed beyond
     * this one is given back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void beforeExecution(PaperAccount account, PaperOrder order, Instant at) {
        if (order.getListId() == null || !isOcoLeg(order)) {
            return;
        }
        Optional<PaperOrder> other = legs(order.getListId()).stream()
                .filter(leg -> isOcoLeg(leg) && !leg.getId().equals(order.getId()) && leg.isOpen())
                .findFirst();
        if (other.isEmpty()) {
            return;
        }
        PaperOrder partner = other.get();
        BigDecimal extra = partner.lockedAmount().subtract(order.lockedAmount());
        if (extra.signum() > 0) {
            wallets.unlock(account, WalletType.SPOT, lockedCoin(partner), extra);
        }
        partner.expire(OTHER_EXECUTED, at);
        feed.order(account, partner);
        events.publishEvent(new PaperOrderCanceled(partner.getId()));
    }

    /**
     * After an order of a group executed: once the working order of an OTO or OTOCO has filled, its pending order or
     * OCO is placed. The group is ALL_DONE once none of its orders works or waits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void afterExecution(PaperAccount account, PaperOrder order, Instant at) {
        if (order.getListId() == null) {
            return;
        }
        PaperOrderList list = lists.findById(order.getListId())
                .orElseThrow(() -> new IllegalStateException("group " + order.getListId() + " is not stored"));
        if (role(order).equals(WORKING) && order.getOrderStatus() == OrderStatus.FILLED) {
            Map<String, PaperOrder> byRole = byRole(legs(list.getId()));
            TradablePair pair = market.tradablePair(list.getPairId(), list.getMarketType())
                    .orElseThrow(() -> new IllegalStateException("pair " + list.getPairId() + " is not tradable"));
            PairCoins coins = market.pairCoins(list.getPairId())
                    .orElseThrow(() -> new IllegalStateException("pair " + list.getPairId() + " is not stored"));
            if (list.getListType() == OrderListType.OTO) {
                activate(account, pair, coins, byRole.get(PENDING), at);
            } else {
                activateOco(account, pair, coins, byRole.get(TAKE_PROFIT), byRole.get(STOP), at);
            }
        }
        refresh(list);
    }

    /**
     * Cancels every order of the group that works or waits, giving back what they locked: once for the two orders of
     * an OCO.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(PaperAccount account, PaperOrderList list, Instant at) {
        cancel(account, list, null, at);
    }

    /**
     * As {@link #cancel(PaperAccount, PaperOrderList, Instant)}, for a reason of the system.
     *
     * @param reason the status reason each cancelled order shows; {@code null} for a cancel of the Trader
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(PaperAccount account, PaperOrderList list, String reason, Instant at) {
        List<PaperOrder> legs = legs(list.getId());
        List<PaperOrder> openOco =
                legs.stream().filter(leg -> isOcoLeg(leg) && leg.isOpen()).toList();
        if (!openOco.isEmpty()) {
            BigDecimal locked = openOco.stream()
                    .map(PaperOrder::lockedAmount)
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
            wallets.unlock(account, WalletType.SPOT, lockedCoin(openOco.get(0)), locked);
        }
        for (PaperOrder leg : legs) {
            if (leg.isOpen() && !isOcoLeg(leg)) {
                wallets.unlock(account, WalletType.SPOT, lockedCoin(leg), leg.lockedAmount());
            }
            if (leg.isOpen() || leg.isPending()) {
                leg.cancel(reason, at);
                feed.order(account, leg);
                events.publishEvent(new PaperOrderCanceled(leg.getId()));
            }
        }
        refresh(list);
    }

    /** The group as the client sees it, its orders the working one first, then the take profit and the stop. */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderListResponse response(PaperOrderList list) {
        String symbol = market.pairListings(List.of(list.getPairId())).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("pair " + list.getPairId() + " is not stored"))
                .symbol();
        List<PaperOrder> legs = legs(list.getId()).stream()
                .sorted(Comparator.comparingInt(PaperOrderLists::rank))
                .toList();
        return new OrderListResponse(
                list.getId(),
                list.getListClientOrderId(),
                list.getListType(),
                list.getListStatus(),
                list.getPairId(),
                symbol,
                legs.stream().map(leg -> PaperResponses.order(leg, symbol)).toList());
    }

    // ------------------------------------------------------------------------------------------
    // Placing
    // ------------------------------------------------------------------------------------------

    private void startOco(
            PaperAccount account,
            TradablePair pair,
            PairCoins coins,
            PaperOrder takeProfit,
            PaperOrder stop,
            Instant at) {
        if (wouldTake(arrival.bookFor(pair, takeProfit.getSide()), takeProfit)) {
            throw new BusinessException(
                    ErrorCode.PAPER_ORDER_WOULD_TAKE, "the OCO's take profit LIMIT_MAKER would execute on arrival");
        }
        CoinListing coin = lockedCoin(takeProfit);
        wallets.preload(account, WalletType.SPOT, coin);
        wallets.lock(account, WalletType.SPOT, coin, takeProfit.lockedAmount().max(stop.lockedAmount()));
        meet(account, pair, coins, takeProfit, stop, at, null);
    }

    /**
     * The two orders of an OCO meet the market: the take profit, a LIMIT_MAKER, waits in the book and the stop waits for
     * its trigger. A take profit the book already reaches when a pending OCO is placed cannot wait as a maker: it
     * expires, giving back what it held beyond the stop, and the stop goes on alone.
     *
     * @param activatedAt when the working order of an OTOCO filled, from which its OCO waits; {@code null} for an OCO
     *     placed now
     */
    private void meet(
            PaperAccount account,
            TradablePair pair,
            PairCoins coins,
            PaperOrder takeProfit,
            PaperOrder stop,
            Instant at,
            Instant activatedAt) {
        if (wouldTake(arrival.bookFor(pair, takeProfit.getSide()), takeProfit)) {
            BigDecimal extra = takeProfit.lockedAmount().subtract(stop.lockedAmount());
            if (extra.signum() > 0) {
                wallets.unlock(account, WalletType.SPOT, lockedCoin(takeProfit), extra);
            }
            takeProfit.expire(SpotArrival.WOULD_TAKE, at);
            feed.order(account, takeProfit);
            arrival.awaitTrigger(account, stop, activatedAt);
            return;
        }
        arrival.rest(account, takeProfit, activatedAt);
        arrival.awaitTrigger(account, stop, activatedAt);
    }

    /** The working order locks what it needs and meets the market; filled at once, its pending part is placed. */
    private void startWorking(
            PaperAccount account, TradablePair pair, PairCoins coins, PaperOrder working, Instant at) {
        CoinListing coin = lockedCoin(working);
        wallets.preload(account, WalletType.SPOT, coin);
        wallets.lock(account, WalletType.SPOT, coin, working.lockedAmount());
        Optional<SpotArrival.Book> book = arrival.bookFor(pair, working.getSide());
        if (working.getOrderType() == OrderType.LIMIT_MAKER) {
            if (wouldTake(book, working)) {
                throw new BusinessException(
                        ErrorCode.PAPER_ORDER_WOULD_TAKE,
                        "the working LIMIT_MAKER of the group would execute on arrival");
            }
            arrival.rest(account, working);
            return;
        }
        arrival.limitOnArrival(account, working, coins, book, at);
        afterExecution(account, working, at);
    }

    /**
     * A pending order of an OTO is placed: it locks what it needs and meets the market, or is REJECTED. A stop whose
     * condition the last price already meets would trigger at once: it ends EXPIRED, locking nothing, and the working
     * order's fill stands.
     */
    private void activate(PaperAccount account, TradablePair pair, PairCoins coins, PaperOrder order, Instant at) {
        if (order == null || !order.isPending()) {
            return;
        }
        if (order.isTriggered() && wouldTriggerNow(pair, order)) {
            order.activate();
            order.expire(WOULD_TRIGGER_ON_ACTIVATION, at);
            feed.order(account, order);
            return;
        }
        CoinListing coin = lockedCoin(order);
        if (wallets.free(account, WalletType.SPOT, coin).compareTo(order.lockedAmount()) < 0) {
            order.reject(INSUFFICIENT, at);
            feed.order(account, order);
            return;
        }
        order.activate();
        wallets.lock(account, WalletType.SPOT, coin, order.lockedAmount());
        feed.order(account, order);
        // From the working order's fill, not the group's placement: no trade before the fill may reach it (D-77).
        switch (order.getOrderType()) {
            case STOP_LOSS, STOP_LOSS_LIMIT, TAKE_PROFIT, TAKE_PROFIT_LIMIT -> arrival.awaitTrigger(account, order, at);
            case LIMIT_MAKER -> {
                Optional<SpotArrival.Book> book = arrival.bookFor(pair, order.getSide());
                if (wouldTake(book, order)) {
                    wallets.unlock(account, WalletType.SPOT, coin, order.lockedAmount());
                    order.expire(SpotArrival.WOULD_TAKE, at);
                } else {
                    arrival.rest(account, order, at);
                }
            }
            default -> arrival.limitOnArrival(account, order, coins, arrival.bookFor(pair, order.getSide()), at, at);
        }
    }

    /**
     * The pending OCO of an OTOCO is placed: both lock once and meet the market, or both are REJECTED. A stop the last
     * price already meets ends EXPIRED, locking nothing, and the take profit goes on alone, locking only what it needs.
     */
    private void activateOco(
            PaperAccount account,
            TradablePair pair,
            PairCoins coins,
            PaperOrder takeProfit,
            PaperOrder stop,
            Instant at) {
        if (takeProfit == null || stop == null || !takeProfit.isPending() || !stop.isPending()) {
            return;
        }
        if (wouldTriggerNow(pair, stop)) {
            stop.activate();
            stop.expire(WOULD_TRIGGER_ON_ACTIVATION, at);
            feed.order(account, stop);
            activate(account, pair, coins, takeProfit, at);
            return;
        }
        CoinListing coin = lockedCoin(takeProfit);
        BigDecimal needed = takeProfit.lockedAmount().max(stop.lockedAmount());
        if (wallets.free(account, WalletType.SPOT, coin).compareTo(needed) < 0) {
            for (PaperOrder leg : List.of(takeProfit, stop)) {
                leg.reject(INSUFFICIENT, at);
                feed.order(account, leg);
            }
            return;
        }
        takeProfit.activate();
        stop.activate();
        wallets.lock(account, WalletType.SPOT, coin, needed);
        feed.order(account, takeProfit);
        feed.order(account, stop);
        meet(account, pair, coins, takeProfit, stop, at, at);
    }

    /** Whether a stop's condition holds at the pair's last price now; without one it waits for the next price. */
    private boolean wouldTriggerNow(TradablePair pair, PaperOrder stop) {
        return market.currentLastPrice(MarketType.SPOT, pair.symbol())
                .map(last -> stop.getTriggerCondition().isMet(last, stop.getStopPrice()))
                .orElse(false);
    }

    private PaperOrder working(PaperAccount account, Quote quote, String key, Leg leg) {
        BigDecimal price = limitPrice(quote, leg.side(), leg.price(), "working.price");
        BigDecimal quantity = quantity(quote.filters(), leg.quantity(), price, "working.quantity");
        UUID pairId = quote.pair().pairId();
        PaperOrder order = leg.type() == OrderType.LIMIT_MAKER
                ? PaperOrder.spotLimitMaker(account.getId(), pairId, key + WORKING, leg.side(), price, quantity)
                : PaperOrder.spotLimit(
                        account.getId(), pairId, key + WORKING, leg.side(), TimeInForce.GTC, price, quantity);
        return orders.save(order.inList(UUID.fromString(key), false));
    }

    /**
     * The pending order of an OTO. A stop is checked against the working order's price, where it starts once that
     * fills, not against the last price: one whose condition already holds there would trigger at once and is refused.
     */
    private void pending(PaperAccount account, Quote quote, String key, Leg leg, BigDecimal workingPrice) {
        OrderType type = leg.type();
        PairFilters filters = quote.filters();
        BigDecimal price = type.isSpotMarketStop() ? null : limitPrice(quote, leg.side(), leg.price(), "pending.price");
        BigDecimal sizing = price != null ? price : price(filters, leg.stopPrice(), "pending.stopPrice");
        BigDecimal quantity = quantity(filters, leg.quantity(), sizing, "pending.quantity");
        UUID pairId = quote.pair().pairId();
        PaperOrder order;
        if (type.isSpotStop()) {
            BigDecimal stop = price(filters, leg.stopPrice(), "pending.stopPrice");
            requireNotMet(type, leg.side(), stop, workingPrice);
            order = PaperOrder.spotStop(
                    account.getId(),
                    pairId,
                    key + PENDING,
                    type,
                    leg.side(),
                    type.isSpotMarketStop() ? null : TimeInForce.GTC,
                    stop,
                    null,
                    price,
                    quantity);
        } else if (type == OrderType.LIMIT_MAKER) {
            order = PaperOrder.spotLimitMaker(account.getId(), pairId, key + PENDING, leg.side(), price, quantity);
        } else {
            order = PaperOrder.spotLimit(
                    account.getId(), pairId, key + PENDING, leg.side(), TimeInForce.GTC, price, quantity);
        }
        orders.save(order.inList(UUID.fromString(key), true));
    }

    /** A stop whose condition already holds at {@code reference} would trigger immediately, and is refused. */
    private static void requireNotMet(OrderType type, OrderSide side, BigDecimal stop, BigDecimal reference) {
        if (type.triggerConditionFor(side).isMet(reference, stop)) {
            throw new BusinessException(
                    ErrorCode.PAPER_ORDER_WOULD_TRIGGER,
                    type + " " + side + " at " + stop.toPlainString() + " would trigger immediately at "
                            + reference.toPlainString());
        }
    }

    /**
     * The quantity the pending OCO of an OTOCO sells or buys when the request gives none: all a BUY working order
     * brings in once the largest Spot fee is taken from it in the coin bought, floored to the step, so the OCO can
     * lock it; all a SELL working order sold, as its fee is taken in the quote.
     *
     * <p>Rule: TR-10; PT-R04.
     */
    private BigDecimal ocoQuantityAfter(PaperOrder working, PairFilters filters) {
        if (working.getSide() == OrderSide.SELL) {
            return working.getOrigQuantity();
        }
        BigDecimal fee = fees.spotMakerFeeRate().max(fees.spotTakerFeeRate());
        return filters.floorQuantity(working.getOrigQuantity().multiply(BigDecimal.ONE.subtract(fee)));
    }

    private PaperOrder takeProfit(
            PaperAccount account, Quote quote, String key, Oco oco, BigDecimal ocoQuantity, boolean pending) {
        BigDecimal price = limitPrice(quote, oco.side(), oco.takeProfitPrice(), "oco.takeProfitPrice");
        BigDecimal quantity = quantity(quote.filters(), ocoQuantity, price, "oco.quantity");
        return orders.save(PaperOrder.spotLimitMaker(
                        account.getId(), quote.pair().pairId(), key + TAKE_PROFIT, oco.side(), price, quantity)
                .inList(UUID.fromString(key), pending));
    }

    private PaperOrder stop(
            PaperAccount account, Quote quote, String key, Oco oco, BigDecimal ocoQuantity, boolean pending) {
        PairFilters filters = quote.filters();
        BigDecimal stopPrice = price(filters, oco.stopPrice(), "oco.stopPrice");
        // Without a limit price the stop executes as a MARKET order once triggered, as the exchange's STOP_LOSS.
        boolean market = oco.stopLimitPrice() == null;
        BigDecimal limit = market ? null : limitPrice(quote, oco.side(), oco.stopLimitPrice(), "oco.stopLimitPrice");
        BigDecimal quantity = quantity(filters, ocoQuantity, market ? stopPrice : limit, "oco.quantity");
        return orders.save(PaperOrder.spotStop(
                        account.getId(),
                        quote.pair().pairId(),
                        key + STOP,
                        market ? OrderType.STOP_LOSS : OrderType.STOP_LOSS_LIMIT,
                        oco.side(),
                        market ? null : TimeInForce.GTC,
                        stopPrice,
                        null,
                        limit,
                        quantity)
                .inList(UUID.fromString(key), pending));
    }

    // ------------------------------------------------------------------------------------------
    // Checks
    // ------------------------------------------------------------------------------------------

    /** Which parts each kind of group takes. */
    private static void requireShape(PlaceOrderListRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.market() != MarketType.SPOT) {
            errors.put("market", "MSG15");
        }
        boolean oco = request.type() != OrderListType.OTO;
        boolean working = request.type() != OrderListType.OCO;
        boolean pending = request.type() == OrderListType.OTO;
        requirePart(errors, "oco", request.oco(), oco);
        requirePart(errors, "working", request.working(), working);
        requirePart(errors, "pending", request.pending(), pending);
        // Only an OTOCO derives the OCO's quantity from its working order.
        if (request.type() == OrderListType.OCO
                && request.oco() != null
                && request.oco().quantity() == null) {
            errors.put("oco.quantity", "MSG01");
        }
        if (request.working() != null && working) {
            requireLeg(errors, "working", request.working(), List.of(OrderType.LIMIT, OrderType.LIMIT_MAKER));
        }
        if (request.pending() != null && pending) {
            requireLeg(
                    errors,
                    "pending",
                    request.pending(),
                    List.of(
                            OrderType.LIMIT,
                            OrderType.LIMIT_MAKER,
                            OrderType.STOP_LOSS,
                            OrderType.STOP_LOSS_LIMIT,
                            OrderType.TAKE_PROFIT,
                            OrderType.TAKE_PROFIT_LIMIT));
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(request.type() + " group with parts that do not fit it", errors);
        }
    }

    private static void requirePart(Map<String, String> errors, String name, Object part, boolean wanted) {
        if (wanted && part == null) {
            errors.put(name, "MSG01");
        } else if (!wanted && part != null) {
            errors.put(name, "MSG15");
        }
    }

    private static void requireLeg(Map<String, String> errors, String name, Leg leg, List<OrderType> types) {
        if (!types.contains(leg.type())) {
            errors.put(name + ".type", "MSG15");
        }
        boolean stop = leg.type().isSpotStop();
        if (stop && leg.stopPrice() == null) {
            errors.put(name + ".stopPrice", "MSG01");
        } else if (!stop && leg.stopPrice() != null) {
            errors.put(name + ".stopPrice", "MSG15");
        }
        boolean market = leg.type().isSpotMarketStop();
        if (!market && leg.price() == null) {
            errors.put(name + ".price", "MSG01");
        } else if (market && leg.price() != null) {
            errors.put(name + ".price", "MSG15");
        }
    }

    /**
     * The take profit of an OCO lies on the far side of where it starts, and its stop on the near side: for a SELL
     * above and below it, for a BUY below and above it, as the exchange requires. An OCO placed now starts at the last
     * price; the pending OCO of an OTOCO at the working order's price, which its fill brings.
     */
    private static void requireOcoAround(Oco oco, BigDecimal reference, String what) {
        boolean sell = oco.side() == OrderSide.SELL;
        int takeProfit = oco.takeProfitPrice().compareTo(reference);
        int stop = oco.stopPrice().compareTo(reference);
        Map<String, String> errors = new LinkedHashMap<>();
        if (sell ? takeProfit <= 0 : takeProfit >= 0) {
            errors.put("oco.takeProfitPrice", "MSG15");
        }
        if (sell ? stop >= 0 : stop <= 0) {
            errors.put("oco.stopPrice", "MSG15");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(
                    "the OCO's prices must lie around " + what + " " + reference.toPlainString(), errors);
        }
    }

    /** A price or stopPrice as given, on the pair's {@code PRICE_FILTER}. */
    private static BigDecimal price(PairFilters filters, BigDecimal price, String field) {
        if (price == null) {
            throw new FieldValidationException(field + " is missing", Map.of(field, "MSG01"));
        }
        SpotFilters.requirePrice(filters, price, field);
        return price;
    }

    /** A limit price as given, on the {@code PRICE_FILTER} and within {@code PERCENT_PRICE_BY_SIDE} of the last price. */
    private static BigDecimal limitPrice(Quote quote, OrderSide side, BigDecimal price, String field) {
        price(quote.filters(), price, field);
        SpotFilters.requirePercentPrice(quote.filters(), side, price, quote.last(), field);
        return price;
    }

    /** A quantity as given, on the {@code LOT_SIZE}, worth within the {@code NOTIONAL} bounds at {@code price}. */
    private static BigDecimal quantity(PairFilters filters, BigDecimal quantity, BigDecimal price, String field) {
        SpotFilters.requireQuantity(filters, quantity, field);
        SpotFilters.requireNotional(filters, price.multiply(quantity), false, field);
        // On the step and within 8 decimals by now, so this only sets the scale a balance holds.
        return quantity.setScale(Rounding.AMOUNT_SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * The pair a group is placed on, with its filters and the last price its limit prices are checked against.
     *
     * @param pair the pair
     * @param filters its Spot filters
     * @param last its last price now
     */
    private record Quote(TradablePair pair, PairFilters filters, BigDecimal last) {}

    private static boolean wouldTake(Optional<SpotArrival.Book> book, PaperOrder order) {
        return book.map(found -> !BookSweep.ofQuantity(
                                found.levels(), order.getSide(), order.remainingQuantity(), order.getLimitPrice())
                        .isEmpty())
                .orElse(false);
    }

    // ------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------

    private List<PaperOrder> legs(UUID listId) {
        return orders.findByListIdOrderByClientOrderIdAsc(listId);
    }

    /** ALL_DONE once none of its orders works or waits. */
    private void refresh(PaperOrderList list) {
        if (list.isExecuting() && legs(list.getId()).stream().noneMatch(leg -> leg.isOpen() || leg.isPending())) {
            list.done();
        }
    }

    private CoinListing lockedCoin(PaperOrder order) {
        return SpotArrival.lockedCoin(
                order,
                market.pairCoins(order.getPairId())
                        .orElseThrow(() -> new IllegalStateException("pair " + order.getPairId() + " is not stored")));
    }

    private static Map<String, PaperOrder> byRole(List<PaperOrder> legs) {
        Map<String, PaperOrder> byRole = new LinkedHashMap<>();
        legs.forEach(leg -> byRole.put(role(leg), leg));
        return byRole;
    }

    static String role(PaperOrder order) {
        String key = order.getClientOrderId();
        return key.substring(key.lastIndexOf(':'));
    }

    private static boolean isOcoLeg(PaperOrder order) {
        String role = role(order);
        return role.equals(TAKE_PROFIT) || role.equals(STOP);
    }

    private static int rank(PaperOrder order) {
        return switch (role(order)) {
            case WORKING -> 0;
            case PENDING -> 1;
            case TAKE_PROFIT -> 2;
            default -> 3;
        };
    }
}
