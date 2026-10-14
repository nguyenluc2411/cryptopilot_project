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
import com.cryptopilot.paper.dto.request.PlaceOrderRequest;
import com.cryptopilot.paper.dto.response.FillResponse;
import com.cryptopilot.paper.dto.response.OrderResponse;
import com.cryptopilot.paper.entity.PaperAccount;
import com.cryptopilot.paper.entity.PaperFill;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.event.PaperOrderAwaitingTrigger;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.matching.BookSweep;
import com.cryptopilot.paper.model.OrderQuery;
import com.cryptopilot.paper.model.PlacedOrder;
import com.cryptopilot.paper.model.TriggerOrder;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TimeInForce;
import com.cryptopilot.paper.model.enums.TriggerCondition;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperFillRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import com.cryptopilot.paper.service.PaperOrderService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
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
 * orders, the order history and the trade history.
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
 * <p>The price is rounded to the pair's tick (BR-30) and the quantity floored to its step (BR-23), then to the 8
 * decimals a balance holds; an order worth less than the pair's minimum notional is refused (MSG15), as the exchange
 * refuses it.
 *
 * <h2>Executing on arrival</h2>
 *
 * <p>A MARKET order, and a LIMIT order that crosses the book, take the levels of the other side of the exchange's book
 * as Binance matches them ({@link BookSweep}, Q-T7): the current depth20, or without one the best bid and ask as a
 * single level, or for a pair without the trading streams the current last price, at which the whole order executes.
 * None is ever executed at a price that is not current (NSF-03).
 *
 * <p>Rule: TR-02, TR-04, TR-06, TR-08, TR-09, TR-11, TR-12; BR-21, BR-23, BR-30; NSF-03; Q-T7.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperOrderServiceImpl implements PaperOrderService {

    /** Decimals a Spot balance holds, so the decimals a quantity may have. */
    private static final int BALANCE_SCALE = Rounding.AMOUNT_SCALE;

    private static final Logger log = LoggerFactory.getLogger(PaperOrderServiceImpl.class);

    private final PaperAccountRepository accounts;
    private final PaperOrderRepository orders;
    private final PaperFillRepository fills;
    private final PaperWallets wallets;
    private final SpotArrival arrival;
    private final UserLock lock;
    private final MarketApi market;
    private final ApplicationEventPublisher events;
    private final PaperUserFeed feed;
    private final Clock clock;

    @Override
    @Transactional
    public PlacedOrder place(UUID userId, PlaceOrderRequest request) {
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        PaperAccount account = accountOf(userId);
        requireShape(request);
        TradablePair pair = market.tradablePair(request.pairId(), request.market())
                .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", request.pairId()));
        Terms terms = termsOf(request, pair.filters());
        if (request.clientOrderId() != null) {
            // Under the lock, so a retry that raced the original waited for it and finds it here.
            Optional<PaperOrder> placed =
                    orders.findByAccountIdAndClientOrderId(account.getId(), request.clientOrderId());
            if (placed.isPresent()) {
                return new PlacedOrder(replayOf(placed.get(), terms, request, pair.symbol()), false);
            }
        }
        PairCoins coins = market.pairCoins(pair.pairId())
                .orElseThrow(() -> new IllegalStateException("pair " + pair.pairId() + " is tradable but not stored"));
        Optional<SpotArrival.Book> book = arrival.bookFor(pair, terms.side());
        String key = request.clientOrderId() == null ? UuidV7.next().toString() : request.clientOrderId();
        PaperOrder order =
                switch (request.type()) {
                    case MARKET -> placeMarket(account, pair, coins, terms, key, book);
                    case LIMIT_MAKER -> placeLimitMaker(account, pair, coins, terms, key, book);
                    case STOP_LIMIT -> placeStopLimit(account, pair, coins, terms, key);
                    case TRAILING_STOP_LIMIT -> placeTrailingStop(account, pair, coins, terms, key);
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
        PaperOrder order = orders.findByIdAndAccountId(orderId, account.getId())
                .orElseThrow(() -> new ResourceNotFoundException("PaperOrder", orderId));
        if (!order.isOpen()) {
            throw new BusinessException(
                    ErrorCode.PAPER_ORDER_NOT_OPEN,
                    "order " + orderId + " is " + order.getOrderStatus() + " and cannot be cancelled",
                    order.getOrderStatus());
        }
        cancelWorking(account, order, coinsOf(order.getPairId()), clock.instant());
        return toResponse(order, symbolsOf(List.of(order.getPairId())));
    }

    @Override
    @Transactional
    public List<OrderResponse> cancelAll(UUID userId, UUID pairId) {
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        PaperAccount account = accountOf(userId);
        List<PaperOrder> open = orders.findByAccountIdAndPairIdAndOrderStatusInOrderByCreatedAtAscIdAsc(
                account.getId(), pairId, OrderStatus.OPEN);
        if (open.isEmpty()) {
            return List.of();
        }
        PairCoins coins = coinsOf(pairId);
        Instant now = clock.instant();
        open.forEach(order -> cancelWorking(account, order, coins, now));
        String symbol = symbolOf(symbolsOf(List.of(pairId)), pairId);
        log.debug("Paper orders of account {} on {} cancelled: {}", account.getId(), symbol, open.size());
        return open.stream().map(order -> toResponse(order, symbol)).toList();
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
        PaperOrder order = orders.findByIdAndAccountId(
                        orderId, accountOf(userId).getId())
                .orElseThrow(() -> new ResourceNotFoundException("PaperOrder", orderId));
        return toResponse(order, symbolsOf(List.of(order.getPairId())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> openOrders(UUID userId, UUID pairId) {
        List<PaperOrder> open = orders
                .findByAccountIdAndOrderStatusInOrderByCreatedAtDescIdDesc(
                        accountOf(userId).getId(), OrderStatus.OPEN)
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
        UUID accountId = accountOf(userId).getId();
        Set<OrderStatus> statuses =
                query.status() == null ? EnumSet.allOf(OrderStatus.class) : EnumSet.of(query.status());
        Instant from = TimeBounds.fromOrEarliest(query.from());
        Instant to = TimeBounds.toOrLatest(query.to());
        Page<PaperOrder> found = query.pairId() == null
                ? orders.search(accountId, statuses, from, to, request)
                : orders.searchPair(accountId, query.pairId(), statuses, from, to, request);
        Map<UUID, String> symbols =
                symbolsOf(found.getContent().stream().map(PaperOrder::getPairId).toList());
        return PageResponse.of(found, order -> toResponse(order, symbols));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<FillResponse> fills(UUID userId, OrderQuery query) {
        PageRequest request = Paging.of(query.page(), query.pageSize());
        TimeBounds.requireOrdered(query.from(), query.to());
        UUID accountId = accountOf(userId).getId();
        Instant from = TimeBounds.fromOrEarliest(query.from());
        Instant to = TimeBounds.toOrLatest(query.to());
        Page<PaperFill> found = query.pairId() == null
                ? fills.search(accountId, from, to, request)
                : fills.searchPair(accountId, query.pairId(), from, to, request);
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
     * needed more than the depth shown. Without a current price it is refused and nothing is stored.
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
            requireTradable(pair.filters(), sweep, "quoteOrderQty");
            order = PaperOrder.spotMarketBuyFor(account.getId(), pair.pairId(), key, terms.quoteAmount());
        } else {
            sweep = BookSweep.ofQuantity(book.levels(), terms.side(), terms.quantity(), null);
            requireTradable(pair.filters(), sweep, "quantity");
            order = PaperOrder.spotMarket(account.getId(), pair.pairId(), key, terms.side(), terms.quantity());
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
     * order the levels cannot fill whole executes nothing and expires, and a post-only (GTX) order that would take a
     * level expires. Without a current price, a GTC or GTX order waits; an IOC or FOK order, which must be decided
     * now, is refused.
     */
    private PaperOrder placeLimit(
            PaperAccount account,
            TradablePair pair,
            PairCoins coins,
            Terms terms,
            String key,
            Optional<SpotArrival.Book> book) {
        requireTradable(pair.filters(), terms.price().multiply(terms.quantity()), terms.quantity(), "quantity");
        TimeInForce timeInForce = terms.timeInForce();
        if (book.isEmpty() && (timeInForce == TimeInForce.IOC || timeInForce == TimeInForce.FOK)) {
            throw priceUnavailable(pair);
        }
        PaperOrder order = orders.save(PaperOrder.spotLimit(
                account.getId(), pair.pairId(), key, terms.side(), timeInForce, terms.price(), terms.quantity()));
        lock(account, order, coins);
        arrival.limitOnArrival(account, order, coins, book, clock.instant());
        return order;
    }

    /**
     * A stop limit locks what its limit order will need, as the exchange does, and waits for the last price to trade at
     * or past its stop. Which way that is follows from where the stop lies against the last price now: above it, the
     * price must rise to it; below it, fall to it. A stop at the last price itself is refused. Without a current
     * price, which way cannot be told, and it is refused.
     */
    private PaperOrder placeStopLimit(
            PaperAccount account, TradablePair pair, PairCoins coins, Terms terms, String key) {
        requireTradable(pair.filters(), terms.price().multiply(terms.quantity()), terms.quantity(), "quantity");
        BigDecimal last =
                market.currentLastPrice(MarketType.SPOT, pair.symbol()).orElseThrow(() -> priceUnavailable(pair));
        int side = terms.stopPrice().compareTo(last);
        if (side == 0) {
            throw new BusinessException(
                    ErrorCode.PAPER_ORDER_WOULD_TRIGGER,
                    "stop " + terms.stopPrice().toPlainString() + " is the last price and would trigger at once");
        }
        TriggerCondition condition = side > 0 ? TriggerCondition.AT_OR_ABOVE : TriggerCondition.AT_OR_BELOW;
        PaperOrder order = orders.save(PaperOrder.spotStopLimit(
                account.getId(),
                pair.pairId(),
                key,
                terms.side(),
                terms.timeInForce(),
                terms.stopPrice(),
                condition,
                terms.price(),
                terms.quantity()));
        lock(account, order, coins);
        awaitTrigger(account, order);
        return order;
    }

    /**
     * A trailing stop locks what its limit order will need and waits: a SELL follows the highest price and triggers
     * when the price falls back by its callback, a BUY follows the lowest and triggers when it rises back by it. With
     * an activation price the price has not reached yet, it starts following once it does; otherwise it follows from
     * the last price now. Without a current price it is refused.
     */
    private PaperOrder placeTrailingStop(
            PaperAccount account, TradablePair pair, PairCoins coins, Terms terms, String key) {
        requireTradable(pair.filters(), terms.price().multiply(terms.quantity()), terms.quantity(), "quantity");
        BigDecimal last =
                market.currentLastPrice(MarketType.SPOT, pair.symbol()).orElseThrow(() -> priceUnavailable(pair));
        BigDecimal activation = terms.activationPrice();
        boolean sell = terms.side() == OrderSide.SELL;
        boolean activeNow =
                activation == null || (sell ? last.compareTo(activation) >= 0 : last.compareTo(activation) <= 0);
        PaperOrder order = orders.save(PaperOrder.spotTrailingStopLimit(
                account.getId(),
                pair.pairId(),
                key,
                terms.side(),
                terms.timeInForce(),
                terms.callbackRate(),
                activation,
                activeNow ? last : null,
                terms.price(),
                terms.quantity()));
        lock(account, order, coins);
        awaitTrigger(account, order);
        return order;
    }

    /** Hands a stop limit or a trailing stop to the matching engine, which watches it once the placement commits. */
    private void awaitTrigger(PaperAccount account, PaperOrder order) {
        events.publishEvent(new PaperOrderAwaitingTrigger(new TriggerOrder(
                order.getId(),
                account.getId(),
                order.getMarketType(),
                order.getPairId(),
                order.getTriggerCondition(),
                order.getOrderType() == OrderType.STOP_LIMIT ? order.getStopPrice() : null,
                order.getCallbackRate(),
                order.getActivationPrice(),
                order.getTrailingExtremePrice(),
                order.getCreatedAt())));
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
        requireTradable(pair.filters(), terms.price().multiply(terms.quantity()), terms.quantity(), "quantity");
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
                account.getId(), pair.pairId(), key, terms.side(), terms.price(), terms.quantity()));
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
        if (request.type() != OrderType.STOP_LIMIT && request.stopPrice() != null) {
            errors.put("stopPrice", "MSG15");
        }
        if (request.type() != OrderType.TRAILING_STOP_LIMIT) {
            if (request.callbackRate() != null) {
                errors.put("callbackRate", "MSG15");
            }
            if (request.activationPrice() != null) {
                errors.put("activationPrice", "MSG15");
            }
        }
        switch (request.type()) {
            case STOP_LIMIT, TRAILING_STOP_LIMIT -> {
                if (request.type() == OrderType.STOP_LIMIT && request.stopPrice() == null) {
                    errors.put("stopPrice", "MSG01");
                }
                if (request.type() == OrderType.TRAILING_STOP_LIMIT && request.callbackRate() == null) {
                    errors.put("callbackRate", "MSG01");
                }
                if (request.price() == null) {
                    errors.put("price", "MSG01");
                }
                if (request.quantity() == null) {
                    errors.put("quantity", "MSG01");
                }
                if (request.timeInForce() == TimeInForce.GTX) {
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
                } else if (request.quoteOrderQty() != null && request.side() == OrderSide.SELL) {
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

    /** The order's figures on the pair's grid: the price to the tick, the quantity to the step and 8 decimals. */
    private static Terms termsOf(PlaceOrderRequest request, PairFilters filters) {
        BigDecimal price = request.price() == null ? null : filters.roundPrice(request.price());
        if (price != null && price.signum() == 0) {
            throw new FieldValidationException("price rounds to zero on the tick", Map.of("price", "MSG15"));
        }
        BigDecimal quantity = request.quantity() == null ? null : scaled(filters.floorQuantity(request.quantity()));
        if (quantity != null && quantity.signum() == 0) {
            throw new FieldValidationException("quantity is below one step", Map.of("quantity", "MSG15"));
        }
        boolean hasTimeInForce = request.type() == OrderType.LIMIT
                || request.type() == OrderType.STOP_LIMIT
                || request.type() == OrderType.TRAILING_STOP_LIMIT;
        TimeInForce timeInForce =
                hasTimeInForce && request.timeInForce() == null ? TimeInForce.GTC : request.timeInForce();
        BigDecimal stopPrice = onTick(filters, request.stopPrice(), "stopPrice");
        BigDecimal activationPrice = onTick(filters, request.activationPrice(), "activationPrice");
        return new Terms(
                request.side(),
                request.type(),
                timeInForce,
                price,
                quantity,
                request.quoteOrderQty(),
                stopPrice,
                request.callbackRate(),
                activationPrice);
    }

    /** A trigger price on the pair's tick; {@code null} stays {@code null}; one that rounds to zero is MSG15. */
    private static BigDecimal onTick(PairFilters filters, BigDecimal price, String field) {
        if (price == null) {
            return null;
        }
        BigDecimal rounded = filters.roundPrice(price);
        if (rounded.signum() == 0) {
            throw new FieldValidationException(field + " rounds to zero on the tick", Map.of(field, "MSG15"));
        }
        return rounded;
    }

    /**
     * The order a retry asks for again. A key reused for a different order is a client error, refused rather than
     * answered with an order the client did not ask for.
     */
    private static OrderResponse replayOf(PaperOrder placed, Terms terms, PlaceOrderRequest request, String symbol) {
        boolean same = placed.getPairId().equals(request.pairId())
                && placed.getMarketType() == request.market()
                && placed.getSide() == terms.side()
                && placed.getOrderType() == terms.type()
                && placed.getTimeInForce() == terms.timeInForce()
                && sameValue(placed.getLimitPrice(), terms.price())
                && sameValue(placed.getOrigQuantity(), terms.quantity())
                && sameValue(placed.getQuoteOrderAmount(), terms.quoteAmount())
                && (placed.getOrderType() != OrderType.STOP_LIMIT
                        || sameValue(placed.getStopPrice(), terms.stopPrice()))
                && sameValue(placed.getCallbackRate(), terms.callbackRate())
                && sameValue(placed.getActivationPrice(), terms.activationPrice());
        if (!same) {
            throw new BusinessException(
                    ErrorCode.DATA_CONFLICT,
                    "clientOrderId " + request.clientOrderId() + " was used for order " + placed.getId()
                            + " of other values");
        }
        return toResponse(placed, symbol);
    }

    private static boolean sameValue(BigDecimal stored, BigDecimal asked) {
        return stored == null ? asked == null : asked != null && stored.compareTo(asked) == 0;
    }

    /** An order that takes the book is refused when what it takes is below the pair's minimum notional (MSG15). */
    private static void requireTradable(PairFilters filters, BookSweep.Result sweep, String field) {
        requireTradable(filters, sweep.notional(), sweep.quantity(), field);
    }

    /** An order below the pair's minimum notional is refused on the field that sized it (MSG15). */
    private static void requireTradable(PairFilters filters, BigDecimal notional, BigDecimal quantity, String field) {
        if (quantity.signum() == 0 || filters.isBelowMinNotional(notional)) {
            throw new FieldValidationException(
                    "order of " + quantity.toPlainString() + " worth " + notional.toPlainString()
                            + " is below the minimum notional of "
                            + filters.minNotional().toPlainString(),
                    Map.of(field, "MSG15"));
        }
    }

    private static BusinessException priceUnavailable(TradablePair pair) {
        return new BusinessException(
                ErrorCode.MARKET_PRICE_UNAVAILABLE, "no current last price for " + pair.symbol() + " on SPOT");
    }

    private static BigDecimal scaled(BigDecimal quantity) {
        return quantity.setScale(BALANCE_SCALE, RoundingMode.DOWN);
    }

    /** The caller's account; MSG41 when it is not opened yet, so the client knows to open it. */
    private PaperAccount accountOf(UUID userId) {
        return accounts.findByUserId(userId).orElseThrow(() -> new ResourceNotFoundException("PaperAccount", userId));
    }

    private PairCoins coinsOf(UUID pairId) {
        return market.pairCoins(pairId)
                .orElseThrow(() -> new IllegalStateException("pair " + pairId + " of a paper order is not stored"));
    }

    // fk_paper_order_pair keeps every pair of an order stored, so a missing one is a defect.
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
     * The order's figures after rounding.
     *
     * @param price the LIMIT price on the tick; {@code null} for MARKET
     * @param quantity the quantity on the step; {@code null} for a MARKET buy by total
     * @param quoteAmount what a MARKET buy by total spends; otherwise {@code null}
     * @param stopPrice a STOP_LIMIT's stop on the tick; otherwise {@code null}
     * @param callbackRate a TRAILING_STOP_LIMIT's callback in percent; otherwise {@code null}
     * @param activationPrice a TRAILING_STOP_LIMIT's activation price on the tick, or {@code null}
     */
    private record Terms(
            OrderSide side,
            OrderType type,
            TimeInForce timeInForce,
            BigDecimal price,
            BigDecimal quantity,
            BigDecimal quoteAmount,
            BigDecimal stopPrice,
            BigDecimal callbackRate,
            BigDecimal activationPrice) {}
}
