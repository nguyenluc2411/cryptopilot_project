package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.common.util.TimeBounds;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.dto.request.PlaceOrderListRequest;
import com.cryptopilot.paper.dto.request.PlaceOrderRequest;
import com.cryptopilot.paper.dto.response.FillResponse;
import com.cryptopilot.paper.dto.response.OrderListResponse;
import com.cryptopilot.paper.dto.response.OrderResponse;
import com.cryptopilot.paper.entity.PaperFill;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.entity.PaperOrderList;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.matching.BookSweep;
import com.cryptopilot.paper.model.OrderQuery;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.PlacedOrder;
import com.cryptopilot.paper.model.PlacedOrderList;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TimeInForce;
import com.cryptopilot.paper.model.enums.TriggerCondition;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperFillRepository;
import com.cryptopilot.paper.repository.PaperOrderListRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import com.cryptopilot.paper.service.PaperOrderService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order use cases of TR-02 for Spot: place MARKET and LIMIT orders, cancel a working one, and read the open
 * orders, the order history and the trade history. Every read and cancel is scoped to Spot: a pair trades on both
 * markets under one id, and its Futures orders are {@link FuturesOrderServiceImpl}'s.
 *
 * <p>Every change runs under the Trader's paper lock (the scope of {@link PaperAccountServiceImpl}), so a placement,
 * a cancel and a fill of the matching engine never interleave on the same account, and an idempotency key is looked
 * up and written without a race. Every balance change goes through {@link PaperWallets}; an execution is settled by
 * {@link SpotSettlement}. A refusal of any kind rolls the whole placement back, so a refused order leaves no row. Each
 * order placed or cancelled is noted for the Trader's order queue ({@link PaperUserFeed}); a replayed key changes
 * nothing and pushes nothing.
 *
 * <h2>Prices and quantities</h2>
 *
 * <p>A price, stopPrice and quantity are taken as given and checked against the pair's Spot filters, as the exchange
 * checks them ({@link SpotFilters}): off the tick or the step, out of their bounds, worth too little or too much, or
 * too far from the last price, the order is refused, never rounded. The open orders and stops of the account on the
 * pair are limited ({@link SpotOrderLimits}).
 *
 * <h2>Executing on arrival</h2>
 *
 * <p>A MARKET order, and a LIMIT order that crosses the book, take the levels of the other side of the exchange's book
 * as Binance matches them ({@link BookSweep}, Q-T7): the current depth20, or without one the best bid and ask as a
 * single level, or for a pair without the trading streams the current last price, at which the whole order executes.
 * None is ever executed at a price that is not current (NSF-03).
 *
 * <p>Rule: TR-02, TR-04, TR-06, TR-08, TR-09, TR-10, TR-11, TR-12; BR-21, BR-23, BR-30; NSF-03; Q-T7.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperOrderServiceImpl implements PaperOrderService {

    /** Decimals a Spot balance holds, so the decimals a quantity may have. */
    private static final int BALANCE_SCALE = Rounding.AMOUNT_SCALE;

    private static final Logger log = LoggerFactory.getLogger(PaperOrderServiceImpl.class);

    /** What the open orders list: the working orders, and the pending orders of groups (TR-10). */
    private static final Set<OrderStatus> LISTED_OPEN =
            EnumSet.of(OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_NEW);

    private final PaperAccountRepository accounts;
    private final PaperOrderRepository orders;
    private final PaperFillRepository fills;
    private final PaperWallets wallets;
    private final SpotArrival arrival;
    private final PaperOrderLists lists;
    private final PaperOrderListRepository groups;
    private final SpotOrderLimits limits;
    private final UserLock lock;
    private final MarketApi market;
    private final QuoteUsdtRate usdtRates;
    private final ApplicationEventPublisher events;
    private final PaperUserFeed feed;
    private final Clock clock;

    @Override
    @Transactional
    public PlacedOrder place(UUID userId, PlaceOrderRequest request) {
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        PaperAccount account = accountOf(userId);
        requireShape(request);
        if (request.clientOrderId() != null) {
            // Under the lock, so a retry that raced the original waited for it and finds it here. Before the pair and
            // filter checks: a retry is answered with what was placed though the pair stopped or its filters changed.
            Optional<PaperOrder> placed =
                    orders.findByUserIdAndClientOrderId(account.userId(), request.clientOrderId());
            if (placed.isPresent()) {
                return new PlacedOrder(replayOf(placed.get(), request), false);
            }
        }
        // A pair the exchange stopped trading takes no new order: its open ones are cancelled (NSF-01).
        TradablePair pair = market.tradablePair(request.pairId(), request.market())
                .filter(TradablePair::exchangeTrading)
                .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", request.pairId()));
        Terms terms = termsOf(request, pair.filters());
        limits.requireRoom(
                account.userId(), pair.pairId(), pair.filters(), 1, terms.type().isSpotStop() ? 1 : 0);
        if (terms.price() != null) {
            SpotFilters.requirePercentPrice(
                    pair.filters(),
                    terms.side(),
                    terms.price(),
                    market.currentLastPrice(MarketType.SPOT, pair.symbol()).orElse(null),
                    "price");
        }
        PairCoins coins = market.pairCoins(pair.pairId())
                .orElseThrow(() -> new IllegalStateException("pair " + pair.pairId() + " is tradable but not stored"));
        usdtRates.requireValued(coins);
        Optional<SpotArrival.Book> book = arrival.bookFor(pair, terms.side());
        String key = request.clientOrderId() == null ? UuidV7.next().toString() : request.clientOrderId();
        PaperOrder order =
                switch (request.type()) {
                    case MARKET -> placeMarket(account, pair, coins, terms, key, book);
                    case LIMIT_MAKER -> placeLimitMaker(account, pair, coins, terms, key, book);
                    case STOP_LOSS, STOP_LOSS_LIMIT, TAKE_PROFIT, TAKE_PROFIT_LIMIT ->
                        placeStop(account, pair, coins, terms, key);
                    default -> placeLimit(account, pair, coins, terms, key, book);
                };
        feed.order(account, order);
        log.debug(
                "Paper order {} {} {} {} placed: {}",
                order.getId(),
                pair.symbol(),
                order.getSide(),
                order.getOrderType(),
                order.getOrderStatus());
        return new PlacedOrder(toResponse(order, pair.symbol()), true);
    }

    @Override
    @Transactional
    public OrderResponse cancel(UUID userId, UUID orderId) {
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        PaperAccount account = accountOf(userId);
        PaperOrder order = orders.findByIdAndUserIdAndMarketType(orderId, account.userId(), MarketType.SPOT)
                .orElseThrow(() -> new ResourceNotFoundException("PaperOrder", orderId));
        if (!order.isOpen() && !order.isPending()) {
            throw new BusinessException(
                    ErrorCode.PAPER_ORDER_NOT_OPEN,
                    "order " + orderId + " is " + order.getOrderStatus() + " and cannot be cancelled",
                    order.getOrderStatus());
        }
        if (order.getListId() != null) {
            // Cancelling any order of a group cancels the group (TR-10).
            lists.cancel(account, groupOf(order.getListId()), clock.instant());
        } else {
            cancelWorking(account, order, coinsOf(order.getPairId()), clock.instant());
        }
        return toResponse(order, symbolsOf(List.of(order.getPairId())));
    }

    @Override
    @Transactional
    public List<OrderResponse> cancelAll(UUID userId, UUID pairId) {
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        PaperAccount account = accountOf(userId);
        List<PaperOrder> open = orders.findByUserIdAndPairIdAndMarketTypeAndOrderStatusInOrderByCreatedAtAscIdAsc(
                account.userId(), pairId, MarketType.SPOT, OrderStatus.OPEN);
        if (open.isEmpty()) {
            return List.of();
        }
        PairCoins coins = coinsOf(pairId);
        Instant now = clock.instant();
        Set<UUID> cancelledGroups = new HashSet<>();
        for (PaperOrder order : open) {
            if (order.getListId() == null) {
                cancelWorking(account, order, coins, now);
            } else if (cancelledGroups.add(order.getListId())) {
                lists.cancel(account, groupOf(order.getListId()), now);
            }
        }
        String symbol = symbolOf(symbolsOf(List.of(pairId)), pairId);
        log.debug("Paper orders of account {} on {} cancelled: {}", account.userId(), symbol, open.size());
        return open.stream().map(order -> toResponse(order, symbol)).toList();
    }

    @Override
    @Transactional
    public PlacedOrderList placeList(UUID userId, PlaceOrderListRequest request) {
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        return lists.place(accountOf(userId), request);
    }

    @Override
    @Transactional
    public OrderListResponse cancelList(UUID userId, UUID listId) {
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        PaperAccount account = accountOf(userId);
        PaperOrderList list = groups.findByIdAndUserId(listId, account.userId())
                .orElseThrow(() -> new ResourceNotFoundException("PaperOrderList", listId));
        if (!list.isExecuting()) {
            throw new BusinessException(
                    ErrorCode.PAPER_ORDER_NOT_OPEN,
                    "group " + listId + " is " + list.getListStatus() + " and cannot be cancelled",
                    list.getListStatus());
        }
        lists.cancel(account, list, clock.instant());
        return lists.response(list);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderListResponse orderList(UUID userId, UUID listId) {
        PaperOrderList list = groups.findByIdAndUserId(listId, accountOf(userId).userId())
                .orElseThrow(() -> new ResourceNotFoundException("PaperOrderList", listId));
        return lists.response(list);
    }

    // fk_trade_order_list keeps the group of an order stored.
    private PaperOrderList groupOf(UUID listId) {
        return groups.findById(listId)
                .orElseThrow(() -> new IllegalStateException("group " + listId + " of a paper order is not stored"));
    }

    /** Gives back what a working order locked and ends it; the engine drops it from its book after the commit. */
    private void cancelWorking(PaperAccount account, PaperOrder order, PairCoins coins, Instant at) {
        wallets.unlock(account, WalletType.SPOT, SpotArrival.lockedCoin(order, coins), order.lockedAmount());
        order.cancel(at);
        feed.order(account, order);
        events.publishEvent(new PaperOrderCanceled(order.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse order(UUID userId, UUID orderId) {
        PaperOrder order = orders.findByIdAndUserIdAndMarketType(
                        orderId, accountOf(userId).userId(), MarketType.SPOT)
                .orElseThrow(() -> new ResourceNotFoundException("PaperOrder", orderId));
        return toResponse(order, symbolsOf(List.of(order.getPairId())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> openOrders(UUID userId, UUID pairId) {
        List<PaperOrder> open = orders
                .findByUserIdAndMarketTypeAndOrderStatusInOrderByCreatedAtDescIdDesc(
                        accountOf(userId).userId(), MarketType.SPOT, LISTED_OPEN)
                .stream()
                .filter(order -> pairId == null || order.getPairId().equals(pairId))
                .toList();
        Map<UUID, String> symbols =
                symbolsOf(open.stream().map(PaperOrder::getPairId).toList());
        return open.stream().map(order -> toResponse(order, symbols)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> orders(UUID userId, OrderQuery query) {
        PageRequest request = Paging.of(query.page(), query.pageSize());
        TimeBounds.requireOrdered(query.from(), query.to());
        // MSG41 for a Trader who has not opened an account, before anything is read.
        accountOf(userId);
        Set<OrderStatus> statuses =
                query.status() == null ? EnumSet.allOf(OrderStatus.class) : EnumSet.of(query.status());
        Instant from = TimeBounds.fromOrEarliest(query.from());
        Instant to = TimeBounds.toOrLatest(query.to());
        Page<PaperOrder> found = query.pairId() == null
                ? orders.search(userId, MarketType.SPOT, statuses, from, to, request)
                : orders.searchPair(userId, query.pairId(), MarketType.SPOT, statuses, from, to, request);
        Map<UUID, String> symbols =
                symbolsOf(found.getContent().stream().map(PaperOrder::getPairId).toList());
        return PageResponse.of(found, order -> toResponse(order, symbols));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<FillResponse> fills(UUID userId, OrderQuery query) {
        PageRequest request = Paging.of(query.page(), query.pageSize());
        TimeBounds.requireOrdered(query.from(), query.to());
        // MSG41 for a Trader who has not opened an account, before anything is read.
        accountOf(userId);
        Instant from = TimeBounds.fromOrEarliest(query.from());
        Instant to = TimeBounds.toOrLatest(query.to());
        Page<PaperFill> found = query.pairId() == null
                ? fills.search(userId, MarketType.SPOT, from, to, request)
                : fills.searchPair(userId, query.pairId(), MarketType.SPOT, from, to, request);
        Map<UUID, String> symbols =
                symbolsOf(found.getContent().stream().map(PaperFill::getPairId).toList());
        Map<UUID, String> assets =
                market
                        .coins(found.getContent().stream()
                                .map(PaperFill::getFeeCoinId)
                                .collect(Collectors.toSet()))
                        .stream()
                        .collect(Collectors.toMap(CoinListing::coinId, CoinListing::symbol));
        return PageResponse.of(found, fill -> toResponse(fill, symbols, assets));
    }

    /**
     * A MARKET order takes the book at once, as a taker, paying from what is free: one fill per level, flagged when it
     * needed more than the depth shown. Sized by {@code quoteOrderQty}, a BUY spends that amount and a SELL sells what
     * brings it in, as on the exchange. Without a current price it is refused and nothing is stored.
     */
    private PaperOrder placeMarket(
            PaperAccount account,
            TradablePair pair,
            PairCoins coins,
            Terms terms,
            String key,
            Optional<SpotArrival.Book> found) {
        SpotArrival.Book book = found.orElseThrow(() -> priceUnavailable(pair));
        BookSweep.Result sweep;
        PaperOrder order;
        if (terms.quoteAmount() != null) {
            sweep = BookSweep.ofQuote(book.levels(), terms.quoteAmount(), pair.filters());
            SpotFilters.requirePositive(pair.filters(), sweep.quantity(), "quoteOrderQty");
            SpotFilters.requireMarketQuantity(pair.filters(), sweep.quantity(), "quoteOrderQty", false);
            SpotFilters.requireNotional(pair.filters(), sweep.notional(), true, "quoteOrderQty");
            order = PaperOrder.spotMarketFor(account.userId(), pair.pairId(), key, terms.side(), terms.quoteAmount());
        } else {
            sweep = BookSweep.ofQuantity(book.levels(), terms.side(), terms.quantity(), null);
            SpotFilters.requirePositive(pair.filters(), sweep.quantity(), "quantity");
            SpotFilters.requireNotional(pair.filters(), sweep.notional(), true, "quantity");
            order = PaperOrder.spotMarket(account.userId(), pair.pairId(), key, terms.side(), terms.quantity());
        }
        orders.save(order);
        Instant now = clock.instant();
        arrival.execute(account, order, coins, sweep, now, false);
        if (order.isOpen()) {
            order.completeFill(now);
        }
        if (sweep.pastBook() && book.shown()) {
            order.flagDepthExhausted();
        }
        return order;
    }

    /**
     * A LIMIT order first locks what it needs, as the exchange checks the balance before it matches. Then it takes the
     * levels at or better than its price, as a taker; what is left waits in the book (GTC) or expires (IOC). A FOK
     * order the levels cannot fill whole executes nothing and expires. Without a current price, a GTC order waits; an
     * IOC or FOK order, which must be decided now, is refused. The post-only order of Spot is LIMIT_MAKER.
     */
    private PaperOrder placeLimit(
            PaperAccount account,
            TradablePair pair,
            PairCoins coins,
            Terms terms,
            String key,
            Optional<SpotArrival.Book> book) {
        SpotFilters.requireNotional(pair.filters(), terms.price().multiply(terms.quantity()), false, "quantity");
        TimeInForce timeInForce = terms.timeInForce();
        if (book.isEmpty() && timeInForce.immediate()) {
            throw priceUnavailable(pair);
        }
        PaperOrder order = orders.save(PaperOrder.spotLimit(
                account.userId(), pair.pairId(), key, terms.side(), timeInForce, terms.price(), terms.quantity()));
        lock(account, order, coins);
        arrival.limitOnArrival(account, order, coins, book, clock.instant());
        return order;
    }

    /**
     * A Spot stop (STOP_LOSS, STOP_LOSS_LIMIT, TAKE_PROFIT, TAKE_PROFIT_LIMIT) locks what it will need, as the exchange
     * does, and waits for the last price to trigger it. Its stop condition is met on the side of the price its type and
     * side fix; a stopPrice that is already met now is refused, as the exchange refuses an order that would trigger
     * immediately. With a trailing delta the stopPrice only says where tracking starts, and may already be met. A
     * market stop that buys holds back what it would pay at its stop, so it needs one. Without a current price it is
     * refused.
     */
    private PaperOrder placeStop(PaperAccount account, TradablePair pair, PairCoins coins, Terms terms, String key) {
        OrderType type = terms.type();
        if (type.isSpotMarketStop() && terms.side() == OrderSide.BUY && terms.stopPrice() == null) {
            throw new FieldValidationException(
                    type + " BUY without a stopPrice cannot hold back what it would pay", Map.of("stopPrice", "MSG01"));
        }
        BigDecimal last =
                market.currentLastPrice(MarketType.SPOT, pair.symbol()).orElseThrow(() -> priceUnavailable(pair));
        BigDecimal sizing =
                terms.price() != null ? terms.price() : terms.stopPrice() != null ? terms.stopPrice() : last;
        SpotFilters.requireNotional(pair.filters(), sizing.multiply(terms.quantity()), false, "quantity");
        TriggerCondition condition = type.triggerConditionFor(terms.side());
        if (terms.stopPrice() != null && terms.trailingPercent() == null) {
            int side = last.compareTo(terms.stopPrice());
            boolean met = condition == TriggerCondition.AT_OR_ABOVE ? side >= 0 : side <= 0;
            if (met) {
                throw new BusinessException(
                        ErrorCode.PAPER_ORDER_WOULD_TRIGGER,
                        type + " " + terms.side() + " at " + terms.stopPrice().toPlainString()
                                + " would trigger immediately at the last price " + last.toPlainString());
            }
        }
        PaperOrder order = orders.save(PaperOrder.spotStop(
                account.userId(),
                pair.pairId(),
                key,
                type,
                terms.side(),
                terms.timeInForce(),
                terms.stopPrice(),
                terms.trailingPercent(),
                terms.price(),
                terms.quantity()));
        lock(account, order, coins);
        arrival.awaitTrigger(account, order);
        return order;
    }

    /** Locks what a working order needs, as the exchange checks the balance before it matches. */
    private void lock(PaperAccount account, PaperOrder order, PairCoins coins) {
        CoinListing locked = SpotArrival.lockedCoin(order, coins);
        wallets.preload(account, WalletType.SPOT, locked);
        wallets.lock(account, WalletType.SPOT, locked, order.lockedAmount());
    }

    /**
     * A LIMIT_MAKER order only ever waits: when its price would take a level of the other side of the book on arrival
     * it is refused and nothing is stored, as the exchange refuses it (TR-12). Without a current price it waits, as a
     * GTC limit does.
     */
    private PaperOrder placeLimitMaker(
            PaperAccount account,
            TradablePair pair,
            PairCoins coins,
            Terms terms,
            String key,
            Optional<SpotArrival.Book> book) {
        SpotFilters.requireNotional(pair.filters(), terms.price().multiply(terms.quantity()), false, "quantity");
        boolean wouldTake = book.map(
                        found -> !BookSweep.ofQuantity(found.levels(), terms.side(), terms.quantity(), terms.price())
                                .isEmpty())
                .orElse(false);
        if (wouldTake) {
            throw new BusinessException(
                    ErrorCode.PAPER_ORDER_WOULD_TAKE,
                    "LIMIT_MAKER " + terms.side() + " at " + terms.price().toPlainString()
                            + " would execute on arrival");
        }
        PaperOrder order = orders.save(PaperOrder.spotLimitMaker(
                account.userId(), pair.pairId(), key, terms.side(), terms.price(), terms.quantity()));
        lock(account, order, coins);
        arrival.rest(account, order);
        return order;
    }

    /** Which fields this stage accepts for the order type, before the pair is looked up. */
    private static void requireShape(PlaceOrderRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.market() != MarketType.SPOT) {
            // Futures orders are the next stage of TR-02.
            errors.put("market", "MSG15");
        }
        boolean stop = request.type().isSpotStop();
        if (!stop && request.stopPrice() != null) {
            errors.put("stopPrice", "MSG15");
        }
        if (!stop && request.trailingDelta() != null) {
            errors.put("trailingDelta", "MSG15");
        }
        switch (request.type()) {
            case STOP_LOSS, TAKE_PROFIT -> {
                if (request.stopPrice() == null && request.trailingDelta() == null) {
                    errors.put("stopPrice", "MSG01");
                }
                if (request.quantity() == null) {
                    errors.put("quantity", "MSG01");
                }
                if (request.price() != null) {
                    errors.put("price", "MSG15");
                }
                if (request.timeInForce() != null) {
                    errors.put("timeInForce", "MSG15");
                }
                if (request.quoteOrderQty() != null) {
                    errors.put("quoteOrderQty", "MSG15");
                }
            }
            case STOP_LOSS_LIMIT, TAKE_PROFIT_LIMIT -> {
                if (request.stopPrice() == null && request.trailingDelta() == null) {
                    errors.put("stopPrice", "MSG01");
                }
                if (request.price() == null) {
                    errors.put("price", "MSG01");
                }
                if (request.quantity() == null) {
                    errors.put("quantity", "MSG01");
                }
                if (request.timeInForce() != null && request.timeInForce().futuresOnly()) {
                    errors.put("timeInForce", "MSG15");
                }
                if (request.quoteOrderQty() != null) {
                    errors.put("quoteOrderQty", "MSG15");
                }
            }
            case LIMIT_MAKER -> {
                if (request.price() == null) {
                    errors.put("price", "MSG01");
                }
                if (request.quantity() == null) {
                    errors.put("quantity", "MSG01");
                }
                if (request.timeInForce() != null) {
                    errors.put("timeInForce", "MSG15");
                }
                if (request.quoteOrderQty() != null) {
                    errors.put("quoteOrderQty", "MSG15");
                }
            }
            case LIMIT -> {
                if (request.price() == null) {
                    errors.put("price", "MSG01");
                }
                if (request.timeInForce() != null && request.timeInForce().futuresOnly()) {
                    // GTX is the post-only of Futures, on Spot a LIMIT_MAKER; GTD is Futures only.
                    errors.put("timeInForce", "MSG15");
                }
                if (request.quantity() == null) {
                    errors.put("quantity", "MSG01");
                }
                if (request.quoteOrderQty() != null) {
                    errors.put("quoteOrderQty", "MSG15");
                }
            }
            case MARKET -> {
                if (request.timeInForce() != null) {
                    errors.put("timeInForce", "MSG15");
                }
                if (request.price() != null) {
                    errors.put("price", "MSG15");
                }
                if (request.quantity() == null && request.quoteOrderQty() == null) {
                    errors.put("quantity", "MSG01");
                } else if (request.quantity() != null && request.quoteOrderQty() != null) {
                    errors.put("quoteOrderQty", "MSG15");
                }
            }
            default -> errors.put("type", "MSG15");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(
                    request.market() + " " + request.type() + " order with fields that do not fit it", errors);
        }
    }

    /**
     * The order's figures as the Trader gave them, each checked against the pair's filters ({@link SpotFilters}): a
     * price or stopPrice off the tick, or a quantity off the step, is refused, never rounded.
     */
    private static Terms termsOf(PlaceOrderRequest request, PairFilters filters) {
        BigDecimal price = request.price();
        if (price != null) {
            SpotFilters.requirePrice(filters, price, "price");
        }
        BigDecimal quantity = request.quantity();
        if (quantity != null) {
            SpotFilters.requireQuantity(filters, quantity, "quantity");
            if (request.type() == OrderType.MARKET) {
                SpotFilters.requireMarketQuantity(filters, quantity, "quantity", true);
            }
            // On the step and within 8 decimals by now, so this only sets the scale a balance holds.
            quantity = quantity.setScale(BALANCE_SCALE, RoundingMode.UNNECESSARY);
        }
        TimeInForce timeInForce = timeInForceOf(request);
        BigDecimal stopPrice = request.stopPrice();
        if (stopPrice != null) {
            SpotFilters.requirePrice(filters, stopPrice, "stopPrice");
        }
        if (request.trailingDelta() != null) {
            SpotFilters.requireTrailingDelta(
                    filters,
                    request.type().triggerConditionFor(request.side()),
                    request.trailingDelta(),
                    "trailingDelta");
        }
        BigDecimal trailingPercent = trailingPercentOf(request);
        return new Terms(
                request.side(),
                request.type(),
                timeInForce,
                price,
                quantity,
                request.quoteOrderQty(),
                stopPrice,
                trailingPercent);
    }

    /** The time in force an order works with: GTC for a limit kind that names none. */
    private static TimeInForce timeInForceOf(PlaceOrderRequest request) {
        boolean hasTimeInForce = request.type() == OrderType.LIMIT
                || request.type() == OrderType.STOP_LOSS_LIMIT
                || request.type() == OrderType.TAKE_PROFIT_LIMIT;
        return hasTimeInForce && request.timeInForce() == null ? TimeInForce.GTC : request.timeInForce();
    }

    /** The trailing delta in percent (the exchange's trailingDelta / 100), or {@code null}. */
    private static BigDecimal trailingPercentOf(PlaceOrderRequest request) {
        return request.trailingDelta() == null ? null : BigDecimal.valueOf(request.trailingDelta(), 2);
    }

    /**
     * The order a retry asks for again, compared with the values the request sent, so a pair that stopped trading or
     * filters changed since do not turn a retry into a refusal. A key reused for a different order is a client error,
     * refused rather than answered with an order the client did not ask for.
     *
     * <p>Reference: Richardson, C. (2018). <i>Microservices Patterns</i>. Manning, ch. 3 (idempotent consumers: a
     * duplicate is answered with the first outcome).
     */
    private OrderResponse replayOf(PaperOrder placed, PlaceOrderRequest request) {
        boolean same = placed.getPairId().equals(request.pairId())
                && placed.getMarketType() == request.market()
                && placed.getSide() == request.side()
                && placed.getOrderType() == request.type()
                && placed.getTimeInForce() == timeInForceOf(request)
                && sameValue(placed.getLimitPrice(), request.price())
                && sameValue(placed.getOrigQuantity(), request.quantity())
                && sameValue(placed.getQuoteOrderAmount(), request.quoteOrderQty())
                && sameValue(placed.getStopPrice(), request.stopPrice())
                && sameValue(placed.getCallbackRate(), trailingPercentOf(request));
        if (!same) {
            throw new BusinessException(
                    ErrorCode.DATA_CONFLICT,
                    "clientOrderId " + request.clientOrderId() + " was used for order " + placed.getId()
                            + " of other values");
        }
        return toResponse(placed, symbolOf(symbolsOf(List.of(placed.getPairId())), placed.getPairId()));
    }

    private static boolean sameValue(BigDecimal stored, BigDecimal asked) {
        return stored == null ? asked == null : asked != null && stored.compareTo(asked) == 0;
    }

    private static BusinessException priceUnavailable(TradablePair pair) {
        return new BusinessException(
                ErrorCode.MARKET_PRICE_UNAVAILABLE, "no current last price for " + pair.symbol() + " on SPOT");
    }

    /** The caller's account; MSG41 when it is not opened yet, so the client knows to open it. */
    private PaperAccount accountOf(UUID userId) {
        return accounts.findByUserId(userId).orElseThrow(() -> new ResourceNotFoundException("PaperAccount", userId));
    }

    private PairCoins coinsOf(UUID pairId) {
        return market.pairCoins(pairId)
                .orElseThrow(() -> new IllegalStateException("pair " + pairId + " of a paper order is not stored"));
    }

    // fk_trade_order_pair keeps every pair of an order stored, so a missing one is a defect.
    private Map<UUID, String> symbolsOf(Collection<UUID> pairIds) {
        if (pairIds.isEmpty()) {
            return Map.of();
        }
        return market.pairListings(Set.copyOf(pairIds)).stream()
                .collect(Collectors.toMap(PairListing::pairId, PairListing::symbol, (a, b) -> a));
    }

    private static OrderResponse toResponse(PaperOrder order, Map<UUID, String> symbols) {
        return toResponse(order, symbolOf(symbols, order.getPairId()));
    }

    private static String symbolOf(Map<UUID, String> symbols, UUID pairId) {
        String symbol = symbols.get(pairId);
        if (symbol == null) {
            throw new IllegalStateException("pair " + pairId + " of a paper order is not stored");
        }
        return symbol;
    }

    private static OrderResponse toResponse(PaperOrder order, String symbol) {
        return PaperResponses.order(order, symbol);
    }

    private static FillResponse toResponse(PaperFill fill, Map<UUID, String> symbols, Map<UUID, String> assets) {
        String asset = Objects.requireNonNull(
                assets.get(fill.getFeeCoinId()),
                () -> "fee coin " + fill.getFeeCoinId() + " of fill " + fill.getId() + " is not stored");
        return PaperResponses.fill(fill, symbolOf(symbols, fill.getPairId()), asset);
    }

    /**
     * The order's figures, checked against the pair's filters.
     *
     * @param price the limit price; {@code null} for MARKET
     * @param quantity the quantity, at the scale of a balance; {@code null} for a MARKET order by total
     * @param quoteAmount what a MARKET order by total spends or brings in; otherwise {@code null}
     * @param stopPrice a stop's stopPrice; otherwise {@code null}
     * @param trailingPercent a stop's trailing delta in percent; otherwise {@code null}
     */
    private record Terms(
            OrderSide side,
            OrderType type,
            TimeInForce timeInForce,
            BigDecimal price,
            BigDecimal quantity,
            BigDecimal quoteAmount,
            BigDecimal stopPrice,
            BigDecimal trailingPercent) {}
}
