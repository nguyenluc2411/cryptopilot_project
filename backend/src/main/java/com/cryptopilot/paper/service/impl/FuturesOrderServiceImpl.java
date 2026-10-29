package com.cryptopilot.paper.service.impl;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.common.util.TimeBounds;
import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.calculator.FuturesMarginMath;
import com.cryptopilot.paper.dto.request.FuturesOrderRequest;
import com.cryptopilot.paper.dto.response.FuturesOrderResponse;
import com.cryptopilot.paper.dto.response.FuturesTradeResponse;
import com.cryptopilot.paper.entity.FuturesAccountConfig;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.entity.FuturesSymbolConfig;
import com.cryptopilot.paper.entity.PaperFill;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.exception.FuturesRejection;
import com.cryptopilot.paper.matching.BookSweep;
import com.cryptopilot.paper.model.FuturesAccountMargin;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.PositionDirection;
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.model.enums.TimeInForce;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperFillRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import com.cryptopilot.paper.service.FuturesOrderService;
import com.cryptopilot.paper.service.impl.FuturesMargins.FuturesPair;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The paper Futures orders of TR-15, one-way mode, as the exchange takes them ({@code POST /fapi/v1/order}):
 *
 * <ul>
 *   <li>The figures are checked against the pair's Futures filters and refused, never rounded: PRICE_FILTER, LOT_SIZE,
 *       MARKET_LOT_SIZE, and MIN_NOTIONAL (at the mark price for MARKET), which a reduce-only order is exempt from.
 *       The arithmetic is {@link SpotFilters}', the rules the same on both markets. PERCENT_PRICE is not checked yet,
 *       as the symbol synchronisation does not read the Futures form of that filter (TR-39).
 *   <li>A reduce-only order needs a position on the other side ({@code -2022}).
 *   <li>The part that would open or increase a position must cost no more than the account has for it
 *       ({@code -2019}): the available balance for a cross pair, the cross wallet's free cash for an isolated one
 *       (4.3 rule 6, 4.7), the cost being the initial margin plus the open loss (4.6). The position after it must stay
 *       within the largest notional the leverage allows ({@code -2027}).
 *   <li>Nothing is locked: the margin of a waiting order is derived ({@link FuturesMargins}).
 *   <li>What opens risk waits for current marks ({@code MARK_STALE}, 8.2.1); closing orders do not.
 * </ul>
 *
 * <p>A MARKET order, and a LIMIT order that crosses the book, take the levels of the other side of the exchange's
 * Futures book ({@link BookSweep}): the current depth20, or without one the best bid and ask, or the last price. Each
 * level is one execution, applied to the position by {@link FuturesSettlement}. What is left of a LIMIT order waits
 * in the book (GTC) or expires (IOC); a FOK order the levels cannot fill whole expires; a GTX order that would take a
 * level is refused ({@code -5022}). The matching engine fills a waiting order later, as on Spot.
 *
 * <p>Every change runs under the Trader's paper lock, the scope of {@link PaperAccountServiceImpl}, shared with Spot
 * and the settings, so nothing on the account interleaves. A refusal rolls the placement back and leaves no row.
 *
 * <p>Rule: TR-15, TR-16, TR-22a; D-127.
 * <p>Reference: Binance. <i>USDⓈ-M Futures API: New Order, Cancel Order, Cancel All Open Orders, Query Order, Current
 * All Open Orders, All Orders, Account Trade List</i>, Binance Developer Center.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesOrderServiceImpl implements FuturesOrderService {

    /** The most open orders of an account on one pair when the exchange sent no {@code MAX_NUM_ORDERS}. */
    static final int DEFAULT_MAX_NUM_ORDERS = 200;

    private static final int DEFAULT_LIMIT = 500;

    private static final int MAX_LIMIT = 1000;

    private static final Set<OrderType> CONDITIONAL = EnumSet.of(
            OrderType.STOP,
            OrderType.STOP_MARKET,
            OrderType.TAKE_PROFIT,
            OrderType.TAKE_PROFIT_MARKET,
            OrderType.TRAILING_STOP_MARKET);

    private static final Logger log = LoggerFactory.getLogger(FuturesOrderServiceImpl.class);

    private final PaperAccountRepository accounts;
    private final PaperOrderRepository orders;
    private final PaperFillRepository fills;
    private final FuturesMargins margins;
    private final FuturesSettlement settlement;
    private final FuturesArrival futuresArrival;
    private final UserLock lock;
    private final MarketApi market;
    private final EntitlementApi entitlements;
    private final ApplicationEventPublisher events;
    private final PaperUserFeed feed;
    private final Clock clock;

    @Override
    @Transactional
    public FuturesOrderResponse place(UUID userId, FuturesOrderRequest request) {
        PaperAccount account = requireFutures(userId);
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        FuturesAccountConfig accountConfig = margins.accountConfig(userId);
        if (accountConfig.liquidating()) {
            throw new FuturesRejection(ErrorCode.PAPER_USER_IN_LIQUIDATION, -2023, "User in liquidation mode now.");
        }
        requireShape(request);
        PositionSide positionSide = request.positionSide() == null ? PositionSide.BOTH : request.positionSide();
        if (accountConfig.isDualSidePosition() || positionSide != PositionSide.BOTH) {
            // Hedge mode is TR-14b; until then every account is one-way, where BOTH is the only side.
            throw new FuturesRejection(
                    ErrorCode.PAPER_POSITION_SIDE,
                    -4061,
                    "Order's position side does not match user's setting.",
                    "positionSide",
                    positionSide);
        }
        if (request.newClientOrderId() != null) {
            // Under the lock, so a retry that raced the original waited for it and finds it here.
            Optional<PaperOrder> placed = orders.findByUserIdAndClientOrderId(userId, request.newClientOrderId());
            if (placed.isPresent()) {
                return replayOf(placed.get(), request);
            }
        }
        FuturesPair pair = margins.pair(request.symbol());
        PairFilters filters = pair.filters();
        boolean marketOrder = request.type() == OrderType.MARKET;
        if (!marketOrder) {
            SpotFilters.requirePrice(filters, request.price(), "price");
        }
        SpotFilters.requireQuantity(filters, request.quantity(), "quantity");
        if (marketOrder) {
            SpotFilters.requireMarketQuantity(filters, request.quantity(), "quantity", true);
        }
        requireRoom(userId, pair);
        FuturesPosition position = margins.openPosition(userId, pair.pairId());
        BigDecimal size = position == null ? BigDecimal.ZERO : position.signedQuantity();
        if (request.reducing()
                && FuturesSettlement.closableBy(request.side(), size).signum() == 0) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_REDUCE_ONLY_REJECTED, -2022, "ReduceOnly Order is rejected.", "reduceOnly");
        }
        BigDecimal mark = market.currentMarkPrice(pair.symbol()).orElse(null);
        BigDecimal last =
                market.currentLastPrice(MarketType.FUTURES, pair.symbol()).orElse(mark);
        Optional<SpotArrival.Book> book = futuresArrival.bookFor(pair.pairId(), pair.symbol(), request.side());
        if (!request.reducing()) {
            BigDecimal valuedAt = marketOrder ? (mark != null ? mark : last) : request.price();
            if (valuedAt == null) {
                // A closing MARKET order on a pair without a mark is valued at the book it will take (8.2.1 rule 2).
                valuedAt =
                        book.map(found -> found.levels().getFirst().price()).orElseThrow(() -> priceUnavailable(pair));
            }
            SpotFilters.requireNotional(filters, valuedAt.multiply(request.quantity()), false, "quantity");
        }
        BigDecimal opening = request.reducing() ? BigDecimal.ZERO : opening(request.side(), request.quantity(), size);
        if (opening.signum() > 0) {
            requireFreshMarks(userId, pair, request, book);
            requireMargin(userId, pair, request, size, opening, last, mark);
        }
        String key = request.newClientOrderId() == null ? UuidV7.next().toString() : request.newClientOrderId();
        PaperOrder order = marketOrder
                ? placeMarket(account, pair, request, key, positionSide, book)
                : placeLimit(account, pair, request, key, positionSide, book);
        feed.futuresOrder(account, order);
        log.debug(
                "Paper Futures order {} {} {} {} placed: {}",
                order.getId(),
                pair.symbol(),
                order.getSide(),
                order.getOrderType(),
                order.getOrderStatus());
        return PaperResponses.futuresOrder(order, pair.symbol());
    }

    @Override
    @Transactional
    public FuturesOrderResponse cancel(UUID userId, String symbol, UUID orderId, String origClientOrderId) {
        PaperAccount account = requireFutures(userId);
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        FuturesPair pair = margins.pair(symbol);
        PaperOrder order = find(userId, pair, orderId, origClientOrderId);
        if (!order.isOpen()) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_ORDER_NOT_OPEN, -2011, "Unknown order sent.", order.getOrderStatus());
        }
        cancelWorking(account, order, clock.instant());
        return PaperResponses.futuresOrder(order, pair.symbol());
    }

    @Override
    @Transactional
    public List<FuturesOrderResponse> cancelAll(UUID userId, String symbol) {
        PaperAccount account = requireFutures(userId);
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        FuturesPair pair = margins.pair(symbol);
        List<PaperOrder> open = orders
                .findByUserIdAndPairIdAndMarketTypeAndOrderStatusInOrderByCreatedAtAscIdAsc(
                        userId, pair.pairId(), MarketType.FUTURES, OrderStatus.OPEN)
                .stream()
                .filter(order -> !order.isConditional())
                .toList();
        Instant now = clock.instant();
        open.forEach(order -> cancelWorking(account, order, now));
        return open.stream()
                .map(order -> PaperResponses.futuresOrder(order, pair.symbol()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public FuturesOrderResponse order(UUID userId, String symbol, UUID orderId, String origClientOrderId) {
        requireFutures(userId);
        FuturesPair pair = margins.pair(symbol);
        return PaperResponses.futuresOrder(find(userId, pair, orderId, origClientOrderId), pair.symbol());
    }

    @Override
    @Transactional(readOnly = true)
    public List<FuturesOrderResponse> openOrders(UUID userId, String symbol) {
        requireFutures(userId);
        UUID pairId = symbol == null ? null : margins.pair(symbol).pairId();
        List<PaperOrder> open = margins.openOrders(userId).stream()
                .filter(order -> pairId == null || order.getPairId().equals(pairId))
                .toList();
        Map<UUID, String> symbols =
                symbolsOf(open.stream().map(PaperOrder::getPairId).toList());
        return open.stream()
                .map(order -> PaperResponses.futuresOrder(order, symbols.get(order.getPairId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FuturesOrderResponse> allOrders(
            UUID userId, String symbol, Instant startTime, Instant endTime, Integer limit) {
        requireFutures(userId);
        TimeBounds.requireOrdered(startTime, endTime);
        FuturesPair pair = margins.pair(symbol);
        List<PaperOrder> found = new ArrayList<>(orders.searchPair(
                        userId,
                        pair.pairId(),
                        MarketType.FUTURES,
                        EnumSet.allOf(OrderStatus.class),
                        TimeBounds.fromOrEarliest(startTime),
                        TimeBounds.toOrLatest(endTime),
                        PageRequest.of(0, limitOf(limit)))
                .getContent());
        found.removeIf(PaperOrder::isConditional);
        Collections.reverse(found);
        return found.stream()
                .map(order -> PaperResponses.futuresOrder(order, pair.symbol()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FuturesTradeResponse> userTrades(
            UUID userId, String symbol, Instant startTime, Instant endTime, Integer limit) {
        requireFutures(userId);
        TimeBounds.requireOrdered(startTime, endTime);
        FuturesPair pair = margins.pair(symbol);
        List<PaperFill> found = new ArrayList<>(fills.searchPair(
                        userId,
                        pair.pairId(),
                        MarketType.FUTURES,
                        TimeBounds.fromOrEarliest(startTime),
                        TimeBounds.toOrLatest(endTime),
                        PageRequest.of(0, limitOf(limit)))
                .getContent());
        Collections.reverse(found);
        String asset = margins.usdt().symbol();
        return found.stream()
                .map(fill -> PaperResponses.futuresTrade(fill, pair.symbol(), asset))
                .toList();
    }

    /**
     * A MARKET order takes the book at once, one execution per level, without a price of its own; what the position
     * did not take (a reduce-only order larger than it, an isolated opening that could not be funded) has ended the
     * order. Without a current price it is refused and nothing is stored.
     */
    private PaperOrder placeMarket(
            PaperAccount account,
            FuturesPair pair,
            FuturesOrderRequest request,
            String key,
            PositionSide positionSide,
            Optional<SpotArrival.Book> book) {
        if (book.isEmpty()) {
            throw priceUnavailable(pair);
        }
        PaperOrder order = orders.save(PaperOrder.futuresMarket(
                account.userId(),
                pair.pairId(),
                key,
                request.side(),
                positionSide,
                request.quantity(),
                request.reducing()));
        futuresArrival.market(account, order, book, clock.instant());
        return order;
    }

    /**
     * A LIMIT order takes the levels at or better than its price, as a taker; what is left waits in the book (GTC) or
     * expires (IOC). A FOK order the levels cannot fill whole executes nothing and expires; a GTX order that would
     * take a level is refused. Without a current price a GTC or GTX order waits; an IOC or FOK one, which must be
     * decided now, is refused.
     */
    private PaperOrder placeLimit(
            PaperAccount account,
            FuturesPair pair,
            FuturesOrderRequest request,
            String key,
            PositionSide positionSide,
            Optional<SpotArrival.Book> book) {
        TimeInForce timeInForce = request.timeInForce() == null ? TimeInForce.GTC : request.timeInForce();
        if (timeInForce == TimeInForce.GTX
                && !FuturesArrival.sweep(book, request.side(), request.quantity(), request.price())
                        .isEmpty()) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_ORDER_WOULD_TAKE,
                    -5022,
                    "Due to the order could not be executed as maker, the Post Only order will be rejected.");
        }
        if ((timeInForce == TimeInForce.IOC || timeInForce == TimeInForce.FOK) && book.isEmpty()) {
            throw priceUnavailable(pair);
        }
        PaperOrder order = orders.save(PaperOrder.futuresLimit(
                account.userId(),
                pair.pairId(),
                key,
                request.side(),
                positionSide,
                timeInForce,
                request.price(),
                request.quantity(),
                request.reducing()));
        futuresArrival.limit(account, order, book, clock.instant());
        return order;
    }

    /**
     * The part of an order that would open or increase a position must be paid for, and the position after it must
     * stay within the leverage's largest notional (4.6 … 4.8).
     */
    private void requireMargin(
            UUID userId,
            FuturesPair pair,
            FuturesOrderRequest request,
            BigDecimal size,
            BigDecimal opening,
            BigDecimal last,
            BigDecimal mark) {
        if (mark == null || last == null) {
            throw priceUnavailable(pair);
        }
        FuturesSymbolConfig config = margins.symbolConfig(userId, pair.pairId(), pair.tiers());
        int leverage = config.getLeverage();
        BigDecimal held = PositionDirection.openedBy(request.side()).sign().signum() == size.signum()
                ? size.abs()
                : BigDecimal.ZERO;
        BigDecimal valuedAt = request.price() != null ? request.price() : mark;
        BigDecimal notional = held.add(opening).multiply(valuedAt);
        if (notional.compareTo(FuturesMarginMath.maxNotionalAtLeverage(pair.tiers(), leverage)) > 0) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_MAX_POSITION_AT_LEVERAGE,
                    -2027,
                    "Exceeded the maximum allowable position at current leverage.");
        }
        BigDecimal cost = FuturesMarginMath.orderCost(request.side(), opening, request.price(), last, mark, leverage);
        FuturesAccountMargin margin = margins.crossState(userId, null).margin();
        BigDecimal budget =
                config.getMarginType() == MarginType.CROSSED ? margin.availableBalance() : margin.crossFreeCash();
        if (cost.compareTo(budget) > 0) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_INSUFFICIENT_MARGIN,
                    -2019,
                    "Margin is insufficient.",
                    cost.toPlainString(),
                    budget.toPlainString());
        }
    }

    /**
     * What opens risk waits for current marks (8.2.1): on a {@code MARK_STALE} pair a MARKET order or a LIMIT order that
     * would execute on arrival is refused, as nothing can be checked against the mark, while a LIMIT order that waits
     * is taken; and while a pair of one of the Trader's cross positions is stale, no order opens risk on another pair.
     * Closing orders never wait for the mark.
     */
    private void requireFreshMarks(
            UUID userId, FuturesPair pair, FuturesOrderRequest request, Optional<SpotArrival.Book> book) {
        if (margins.freshMark(pair.symbol()).isEmpty()) {
            boolean takes = request.type() == OrderType.MARKET
                    || book.filter(found -> found.shown()
                                    && crosses(
                                            request.side(),
                                            request.price(),
                                            found.levels().getFirst().price()))
                            .isPresent();
            if (takes) {
                throw FuturesMargins.staleMark(pair.symbol());
            }
        }
        margins.requireFreshCrossMarks(userId, pair.pairId());
    }

    /** Whether a limit price reaches the best price of the other side. */
    private static boolean crosses(OrderSide side, BigDecimal limit, BigDecimal best) {
        return side == OrderSide.BUY ? limit.compareTo(best) >= 0 : limit.compareTo(best) <= 0;
    }

    /** {@code MAX_NUM_ORDERS}: the open orders of the account on the pair, this one included. */
    private void requireRoom(UUID userId, FuturesPair pair) {
        Integer max = pair.filters().rules().maxNumOrders();
        int limit = max != null ? max : DEFAULT_MAX_NUM_ORDERS;
        long open = orders.countByUserIdAndPairIdAndMarketTypeAndOrderStatusIn(
                userId, pair.pairId(), MarketType.FUTURES, OrderStatus.OPEN);
        if (open + 1 > limit) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_MAX_NUM_ORDERS,
                    -2025,
                    "Reach max open order limit.",
                    "orders",
                    open + 1,
                    limit,
                    SpotFilters.MAX);
        }
    }

    /** Cancels a working order; the engine drops it from its book after the commit. Nothing was locked. */
    private void cancelWorking(PaperAccount account, PaperOrder order, Instant at) {
        order.cancel(at);
        feed.futuresOrder(account, order);
        events.publishEvent(new PaperOrderCanceled(order.getId()));
    }

    /**
     * The part of an order of {@code quantity} that would open or increase a one-way position of signed
     * {@code size}: all of it on the same side or when flat, what is left past the position on the other side.
     */
    static BigDecimal opening(OrderSide side, BigDecimal quantity, BigDecimal size) {
        BigDecimal closable = FuturesSettlement.closableBy(side, size);
        return quantity.subtract(closable).max(BigDecimal.ZERO);
    }

    /** Which fields the order type takes, before the pair is looked up. */
    private static void requireShape(FuturesOrderRequest request) {
        if (CONDITIONAL.contains(request.type())) {
            // The exchange's -4120: conditional orders are algo orders (TR-20).
            throw new FieldValidationException(
                    request.type() + " is an algo order, not taken by this endpoint", Map.of("type", "MSG15"));
        }
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.type() != OrderType.MARKET && request.type() != OrderType.LIMIT) {
            errors.put("type", "MSG15");
        }
        if (request.quantity() == null) {
            errors.put("quantity", "MSG01");
        }
        if (request.type() == OrderType.LIMIT && request.price() == null) {
            errors.put("price", "MSG01");
        }
        if (request.type() == OrderType.MARKET && request.price() != null) {
            errors.put("price", "MSG15");
        }
        if (request.type() == OrderType.MARKET && request.timeInForce() != null) {
            errors.put("timeInForce", "MSG15");
        }
        if (Boolean.TRUE.equals(request.closePosition())) {
            errors.put("closePosition", "MSG15");
        }
        if (request.stopPrice() != null) {
            errors.put("stopPrice", "MSG15");
        }
        if (request.priceMatch() != null && !request.priceMatch().equals("NONE")) {
            errors.put("priceMatch", "MSG15");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(
                    "FUTURES " + request.type() + " order with fields that do not fit it", errors);
        }
    }

    /**
     * The order a retry asks for again, compared with what the request sent; a key reused for a different order is a
     * client error, refused rather than answered with an order the client did not ask for.
     */
    private FuturesOrderResponse replayOf(PaperOrder placed, FuturesOrderRequest request) {
        FuturesPair pair = margins.pair(request.symbol());
        TimeInForce timeInForce = request.type() == OrderType.LIMIT && request.timeInForce() == null
                ? TimeInForce.GTC
                : request.timeInForce();
        boolean same = placed.getMarketType() == MarketType.FUTURES
                && placed.getPairId().equals(pair.pairId())
                && placed.getSide() == request.side()
                && placed.getOrderType() == request.type()
                && placed.getTimeInForce() == timeInForce
                && placed.isReduceOnly() == request.reducing()
                && sameValue(placed.getLimitPrice(), request.price())
                && sameValue(placed.getOrigQuantity(), request.quantity());
        if (!same) {
            throw new BusinessException(
                    ErrorCode.DATA_CONFLICT,
                    "newClientOrderId " + request.newClientOrderId() + " was used for order " + placed.getId()
                            + " of other values");
        }
        return PaperResponses.futuresOrder(placed, pair.symbol());
    }

    private PaperOrder find(UUID userId, FuturesPair pair, UUID orderId, String origClientOrderId) {
        if (orderId == null && origClientOrderId == null) {
            throw new FieldValidationException(
                    "either orderId or origClientOrderId must be sent", Map.of("orderId", "MSG01"));
        }
        Optional<PaperOrder> found = orderId != null
                ? orders.findByIdAndUserIdAndMarketType(orderId, userId, MarketType.FUTURES)
                : orders.findByUserIdAndClientOrderIdAndMarketType(userId, origClientOrderId, MarketType.FUTURES);
        return found.filter(order -> order.getPairId().equals(pair.pairId()) && !order.isConditional())
                .orElseThrow(() ->
                        new ResourceNotFoundException("PaperOrder", orderId != null ? orderId : origClientOrderId));
    }

    /** The Futures feature of the Trader's plan (Q-T10) and an opened paper account (MSG41). */
    private PaperAccount requireFutures(UUID userId) {
        entitlements.requireFeature(userId, Feature.FUTURES_ANALYSIS);
        return accounts.findByUserId(userId).orElseThrow(() -> new ResourceNotFoundException("PaperAccount", userId));
    }

    private Map<UUID, String> symbolsOf(List<UUID> pairIds) {
        Map<UUID, String> symbols = new LinkedHashMap<>();
        if (!pairIds.isEmpty()) {
            market.pairListings(Set.copyOf(pairIds)).forEach(pair -> symbols.put(pair.pairId(), pair.symbol()));
        }
        return symbols;
    }

    private static int limitOf(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new FieldValidationException("limit must be 1 to " + MAX_LIMIT, Map.of("limit", "MSG15"));
        }
        return limit;
    }

    private static boolean sameValue(BigDecimal stored, BigDecimal asked) {
        return stored == null ? asked == null : asked != null && stored.compareTo(asked) == 0;
    }

    private static BusinessException priceUnavailable(FuturesPair pair) {
        return new BusinessException(
                ErrorCode.MARKET_PRICE_UNAVAILABLE, "no current price for " + pair.symbol() + " on FUTURES");
    }
}
