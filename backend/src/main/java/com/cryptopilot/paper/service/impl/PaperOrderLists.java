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
import com.cryptopilot.paper.dto.request.PlaceOrderListRequest;
import com.cryptopilot.paper.dto.request.PlaceOrderListRequest.Leg;
import com.cryptopilot.paper.dto.request.PlaceOrderListRequest.Oco;
import com.cryptopilot.paper.dto.response.OrderListResponse;
import com.cryptopilot.paper.entity.PaperAccount;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.entity.PaperOrderList;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.matching.BookSweep;
import com.cryptopilot.paper.model.enums.OrderListType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TimeInForce;
import com.cryptopilot.paper.model.enums.TriggerCondition;
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
 * <p>The pending order of an OTO, or the pending OCO of an OTOCO, locks nothing while it waits. Once the working order
 * has filled in full, it is placed: it locks what it needs and meets the market as an order placed then does. When too
 * little is free by then — the fee of a buy is taken in the coin bought — it is REJECTED
 * ({@code INSUFFICIENT_BALANCE}) and the working order's fill stands.
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

    private final PaperOrderRepository orders;
    private final PaperOrderListRepository lists;
    private final PaperWallets wallets;
    private final SpotArrival arrival;
    private final PaperUserFeed feed;
    private final ApplicationEventPublisher events;
    private final MarketApi market;
    private final QuoteUsdtRate usdtRates;
    private final Clock clock;

    /** Places a group: see the class comment and {@link PlaceOrderListRequest}. A refusal stores nothing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderListResponse place(PaperAccount account, PlaceOrderListRequest request) {
        requireShape(request);
        TradablePair pair = market.tradablePair(request.pairId(), request.market())
                .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", request.pairId()));
        PairCoins coins = market.pairCoins(pair.pairId())
                .orElseThrow(() -> new IllegalStateException("pair " + pair.pairId() + " is tradable but not stored"));
        usdtRates.requireValued(coins);
        PairFilters filters = pair.filters();
        BigDecimal last = market.currentLastPrice(MarketType.SPOT, pair.symbol())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.MARKET_PRICE_UNAVAILABLE, "no current last price for " + pair.symbol() + " on SPOT"));
        PaperOrderList list = lists.save(PaperOrderList.spot(account.getId(), pair.pairId(), request.type()));
        String key = list.getId().toString();
        Instant now = clock.instant();
        switch (request.type()) {
            case OCO -> {
                Oco oco = request.oco();
                requireOcoAroundLast(oco, filters, last);
                PaperOrder takeProfit = takeProfit(account, pair, key, oco, filters, false);
                PaperOrder stop = stop(account, pair, key, oco, filters, false);
                startOco(account, pair, coins, takeProfit, stop, now);
            }
            case OTO -> {
                PaperOrder working = working(account, pair, key, request.working(), filters);
                pending(account, pair, key, request.pending(), filters, last);
                startWorking(account, pair, coins, working, now);
            }
            case OTOCO -> {
                PaperOrder working = working(account, pair, key, request.working(), filters);
                requireOcoOrdered(request.oco(), filters);
                takeProfit(account, pair, key, request.oco(), filters, true);
                stop(account, pair, key, request.oco(), filters, true);
                startWorking(account, pair, coins, working, now);
            }
        }
        refresh(list);
        return response(list);
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
                leg.cancel(at);
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
        meet(account, pair, coins, takeProfit, stop, at);
    }

    /**
     * The two orders of an OCO meet the market: the take profit, a LIMIT_MAKER, waits in the book and the stop waits for
     * its trigger. A take profit the book already reaches when a pending OCO is placed cannot wait as a maker: it
     * expires, giving back what it held beyond the stop, and the stop goes on alone.
     */
    private void meet(
            PaperAccount account,
            TradablePair pair,
            PairCoins coins,
            PaperOrder takeProfit,
            PaperOrder stop,
            Instant at) {
        if (wouldTake(arrival.bookFor(pair, takeProfit.getSide()), takeProfit)) {
            BigDecimal extra = takeProfit.lockedAmount().subtract(stop.lockedAmount());
            if (extra.signum() > 0) {
                wallets.unlock(account, WalletType.SPOT, lockedCoin(takeProfit), extra);
            }
            takeProfit.expire(SpotArrival.WOULD_TAKE, at);
            feed.order(account, takeProfit);
            arrival.awaitTrigger(account, stop);
            return;
        }
        arrival.rest(account, takeProfit);
        arrival.awaitTrigger(account, stop);
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

    /** A pending order of an OTO is placed: it locks what it needs and meets the market, or is REJECTED. */
    private void activate(PaperAccount account, TradablePair pair, PairCoins coins, PaperOrder order, Instant at) {
        if (order == null || !order.isPending()) {
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
        switch (order.getOrderType()) {
            case STOP_LOSS, STOP_LOSS_LIMIT, TAKE_PROFIT, TAKE_PROFIT_LIMIT -> arrival.awaitTrigger(account, order);
            case LIMIT_MAKER -> {
                Optional<SpotArrival.Book> book = arrival.bookFor(pair, order.getSide());
                if (wouldTake(book, order)) {
                    wallets.unlock(account, WalletType.SPOT, coin, order.lockedAmount());
                    order.expire(SpotArrival.WOULD_TAKE, at);
                } else {
                    arrival.rest(account, order);
                }
            }
            default -> arrival.limitOnArrival(account, order, coins, arrival.bookFor(pair, order.getSide()), at);
        }
    }

    /** The pending OCO of an OTOCO is placed: both lock once and meet the market, or both are REJECTED. */
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
        meet(account, pair, coins, takeProfit, stop, at);
    }

    private PaperOrder working(PaperAccount account, TradablePair pair, String key, Leg leg, PairFilters filters) {
        BigDecimal price = price(filters, leg.price(), "working.price");
        BigDecimal quantity = quantity(filters, leg.quantity(), price, "working.quantity");
        PaperOrder order = leg.type() == OrderType.LIMIT_MAKER
                ? PaperOrder.spotLimitMaker(account.getId(), pair.pairId(), key + WORKING, leg.side(), price, quantity)
                : PaperOrder.spotLimit(
                        account.getId(), pair.pairId(), key + WORKING, leg.side(), TimeInForce.GTC, price, quantity);
        return orders.save(order.inList(UUID.fromString(key), false));
    }

    private void pending(
            PaperAccount account, TradablePair pair, String key, Leg leg, PairFilters filters, BigDecimal last) {
        OrderType type = leg.type();
        BigDecimal price = type.isSpotMarketStop() ? null : price(filters, leg.price(), "pending.price");
        BigDecimal sizing = price != null ? price : price(filters, leg.stopPrice(), "pending.stopPrice");
        BigDecimal quantity = quantity(filters, leg.quantity(), sizing, "pending.quantity");
        PaperOrder order;
        if (type.isSpotStop()) {
            BigDecimal stop = price(filters, leg.stopPrice(), "pending.stopPrice");
            requireNotMet(type, leg.side(), stop, last);
            order = PaperOrder.spotStop(
                    account.getId(),
                    pair.pairId(),
                    key + PENDING,
                    type,
                    leg.side(),
                    type.isSpotMarketStop() ? null : TimeInForce.GTC,
                    stop,
                    null,
                    price,
                    quantity);
        } else if (type == OrderType.LIMIT_MAKER) {
            order = PaperOrder.spotLimitMaker(
                    account.getId(), pair.pairId(), key + PENDING, leg.side(), price, quantity);
        } else {
            order = PaperOrder.spotLimit(
                    account.getId(), pair.pairId(), key + PENDING, leg.side(), TimeInForce.GTC, price, quantity);
        }
        orders.save(order.inList(UUID.fromString(key), true));
    }

    /** A stop whose condition the last price already meets would trigger immediately, and is refused. */
    private static void requireNotMet(OrderType type, OrderSide side, BigDecimal stop, BigDecimal last) {
        int at = last.compareTo(stop);
        boolean met = type.triggerConditionFor(side) == TriggerCondition.AT_OR_ABOVE ? at >= 0 : at <= 0;
        if (met) {
            throw new BusinessException(
                    ErrorCode.PAPER_ORDER_WOULD_TRIGGER,
                    type + " " + side + " at " + stop.toPlainString() + " would trigger immediately");
        }
    }

    private PaperOrder takeProfit(
            PaperAccount account, TradablePair pair, String key, Oco oco, PairFilters filters, boolean pending) {
        BigDecimal price = price(filters, oco.takeProfitPrice(), "oco.takeProfitPrice");
        BigDecimal quantity = quantity(filters, oco.quantity(), price, "oco.quantity");
        return orders.save(PaperOrder.spotLimitMaker(
                        account.getId(), pair.pairId(), key + TAKE_PROFIT, oco.side(), price, quantity)
                .inList(UUID.fromString(key), pending));
    }

    private PaperOrder stop(
            PaperAccount account, TradablePair pair, String key, Oco oco, PairFilters filters, boolean pending) {
        BigDecimal stopPrice = price(filters, oco.stopPrice(), "oco.stopPrice");
        // Without a limit price the stop executes as a MARKET order once triggered, as the exchange's STOP_LOSS.
        boolean market = oco.stopLimitPrice() == null;
        BigDecimal limit = market ? null : price(filters, oco.stopLimitPrice(), "oco.stopLimitPrice");
        BigDecimal quantity = quantity(filters, oco.quantity(), market ? stopPrice : limit, "oco.quantity");
        return orders.save(PaperOrder.spotStop(
                        account.getId(),
                        pair.pairId(),
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
     * The take profit of an OCO placed now lies on the far side of the last price, and its stop on the near side: for
     * a SELL above and below it, for a BUY below and above it, as the exchange requires.
     */
    private static void requireOcoAroundLast(Oco oco, PairFilters filters, BigDecimal last) {
        BigDecimal takeProfit = filters.roundPrice(oco.takeProfitPrice());
        BigDecimal stop = filters.roundPrice(oco.stopPrice());
        boolean sell = oco.side() == OrderSide.SELL;
        Map<String, String> errors = new LinkedHashMap<>();
        if (sell ? takeProfit.compareTo(last) <= 0 : takeProfit.compareTo(last) >= 0) {
            errors.put("oco.takeProfitPrice", "MSG15");
        }
        if (sell ? stop.compareTo(last) >= 0 : stop.compareTo(last) <= 0) {
            errors.put("oco.stopPrice", "MSG15");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(
                    "the OCO's prices must lie around the last price " + last.toPlainString(), errors);
        }
    }

    /** The pending OCO of an OTOCO: its take profit beyond its stop, on the side it sells or buys. */
    private static void requireOcoOrdered(Oco oco, PairFilters filters) {
        BigDecimal takeProfit = filters.roundPrice(oco.takeProfitPrice());
        BigDecimal stop = filters.roundPrice(oco.stopPrice());
        boolean sell = oco.side() == OrderSide.SELL;
        if (sell ? takeProfit.compareTo(stop) <= 0 : takeProfit.compareTo(stop) >= 0) {
            throw new FieldValidationException(
                    "the OCO's take profit must lie beyond its stop", Map.of("oco.takeProfitPrice", "MSG15"));
        }
    }

    private static BigDecimal price(PairFilters filters, BigDecimal price, String field) {
        if (price == null) {
            throw new FieldValidationException(field + " is missing", Map.of(field, "MSG01"));
        }
        BigDecimal rounded = filters.roundPrice(price);
        if (rounded.signum() == 0) {
            throw new FieldValidationException(field + " rounds to zero on the tick", Map.of(field, "MSG15"));
        }
        return rounded;
    }

    /** The quantity on the step and 8 decimals, worth at least the minimum notional at {@code price} (MSG15). */
    private static BigDecimal quantity(PairFilters filters, BigDecimal quantity, BigDecimal price, String field) {
        BigDecimal floored = filters.floorQuantity(quantity).setScale(Rounding.AMOUNT_SCALE, RoundingMode.DOWN);
        if (floored.signum() == 0 || filters.isBelowMinNotional(price.multiply(floored))) {
            throw new FieldValidationException(
                    field + " " + quantity.toPlainString() + " is below one step or the minimum notional",
                    Map.of(field, "MSG15"));
        }
        return floored;
    }

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
