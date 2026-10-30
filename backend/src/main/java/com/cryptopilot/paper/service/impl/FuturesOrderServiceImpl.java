package com.cryptopilot.paper.service.impl;

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
import com.cryptopilot.paper.dto.request.FuturesAmendOrderRequest;
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
import com.cryptopilot.paper.model.enums.WorkingType;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperFillRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import com.cryptopilot.paper.service.FuturesOrderService;
import com.cryptopilot.paper.service.impl.FuturesMargins.FuturesPair;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
 *   <li>The figures are checked against the pair's Futures filters and refused, never rounded: PRICE_FILTER (tick,
 *       minPrice, maxPrice), LOT_SIZE (step, minQty, maxQty), MARKET_LOT_SIZE, MIN_NOTIONAL (at the mark price for
 *       MARKET), which a reduce-only order is exempt from, and PERCENT_PRICE against the mark (TR-39). The arithmetic is
 *       {@link SpotFilters}', the rules the same on both markets.
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

    /** Why a GTD order expired: its date came (TR-44). */
    static final String GOOD_TILL_DATE = "GOOD_TILL_DATE";

    /** How far ahead a GTD order's date must be, as the exchange's 600 seconds. */
    private static final Duration GOOD_TILL_MIN_AHEAD = Duration.ofSeconds(600);

    /** The exchange's latest goodTillDate, 9999-12-31T23:59:59Z. */
    private static final Instant GOOD_TILL_LATEST = Instant.ofEpochMilli(253402300799000L);

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
    private final FuturesOrderLists tpSl;
    private final UserLock lock;
    private final MarketApi market;
    private final ApplicationEventPublisher events;
    private final PaperUserFeed feed;
    private final EntityManager entityManager;
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
        requireGoodTillDate(request.goodTillDate());
        PositionSide positionSide =
                FuturesMargins.positionSide(accountConfig, request.positionSide(), request.reduceOnly() != null);
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
        FuturesPosition position = margins.openPosition(userId, pair.pairId(), positionSide);
        BigDecimal size = position == null ? BigDecimal.ZERO : position.signedQuantity();
        // In hedge mode SELL on LONG and BUY on SHORT close their side and never more than it holds (plan 4.4).
        boolean hedgeClose = closesHedgeSide(positionSide, request.side());
        boolean reducing = request.reducing() || hedgeClose;
        BigDecimal closable = FuturesSettlement.closableBy(request.side(), size);
        if ((reducing && closable.signum() == 0)
                || (hedgeClose && request.quantity().compareTo(closable) > 0)) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_REDUCE_ONLY_REJECTED, -2022, "ReduceOnly Order is rejected.", "reduceOnly");
        }
        BigDecimal mark = market.currentMarkPrice(pair.symbol()).orElse(null);
        BigDecimal last =
                market.currentLastPrice(MarketType.FUTURES, pair.symbol()).orElse(mark);
        // Read once: whether the mark is current decides the book, the stale-mark rules and PERCENT_PRICE alike.
        boolean markFresh = margins.freshMark(pair.symbol()).isPresent();
        Optional<SpotArrival.Book> book =
                futuresArrival.bookFor(pair.pairId(), pair.symbol(), request.side(), markFresh);
        if (!reducing) {
            BigDecimal valuedAt = marketOrder ? (mark != null ? mark : last) : request.price();
            if (valuedAt == null) {
                // A closing MARKET order on a pair without a mark is valued at the book it will take (8.2.1 rule 2).
                valuedAt =
                        book.map(found -> found.levels().getFirst().price()).orElseThrow(() -> priceUnavailable(pair));
            }
            SpotFilters.requireNotional(filters, valuedAt.multiply(request.quantity()), false, "quantity");
        }
        BigDecimal opening = reducing ? BigDecimal.ZERO : opening(request.side(), request.quantity(), size);
        // A stale mark is told first, before PERCENT_PRICE, which is judged at the mark (8.2.1).
        if (opening.signum() > 0) {
            requireFreshMarks(userId, pair, request.side(), request.price(), markFresh);
        }
        requirePercentPrice(
                pair.filters(), request.side(), request.price(), percentPriceMark(mark, markFresh, opening), book);
        if (request.withTpSl()) {
            requireTpSl(filters, request, request.price() != null ? request.price() : mark != null ? mark : last);
        }
        if (opening.signum() > 0) {
            requireMargin(userId, pair, request.side(), request.price(), size, opening, last, mark, null);
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
    public FuturesOrderResponse amend(UUID userId, FuturesAmendOrderRequest request) {
        PaperAccount account = requireFutures(userId);
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        if (margins.accountConfig(userId).liquidating()) {
            throw new FuturesRejection(ErrorCode.PAPER_USER_IN_LIQUIDATION, -2023, "User in liquidation mode now.");
        }
        if (request.priceMatch() != null && !request.priceMatch().equals("NONE")) {
            throw new FieldValidationException("priceMatch is not simulated", Map.of("priceMatch", "MSG15"));
        }
        FuturesPair pair = margins.pair(request.symbol());
        PaperOrder order = find(userId, pair, request.orderId(), request.origClientOrderId());
        if (!order.isOpen()) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_ORDER_NOT_OPEN, -2011, "Unknown order sent.", order.getOrderStatus());
        }
        Map<String, String> errors = new LinkedHashMap<>();
        if (order.getOrderType() != OrderType.LIMIT) {
            errors.put("orderId", "MSG15");
        }
        if (order.getSide() != request.side()) {
            errors.put("side", "MSG15");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException("only a LIMIT order of the same side can be amended", errors);
        }
        if (order.getAmendCount() >= PaperOrder.MAX_AMENDMENTS) {
            throw new FuturesRejection(ErrorCode.PAPER_MAX_AMENDMENTS, -5026, "Exceed maximum modify order limit.");
        }
        if (order.getLimitPrice().compareTo(request.price()) == 0
                && order.getOrigQuantity().compareTo(request.quantity()) == 0) {
            throw new FuturesRejection(ErrorCode.PAPER_ORDER_UNCHANGED, -5027, "No need to modify the order.");
        }
        // A new price or quantity the filters refuse leaves the order as it was.
        PairFilters filters = pair.filters();
        SpotFilters.requirePrice(filters, request.price(), "price");
        SpotFilters.requireQuantity(filters, request.quantity(), "quantity");
        Instant now = clock.instant();
        BigDecimal remaining = request.quantity().subtract(order.getExecutedQuantity());
        boolean markFresh = margins.freshMark(pair.symbol()).isPresent();
        Optional<SpotArrival.Book> book =
                futuresArrival.bookFor(pair.pairId(), pair.symbol(), order.getSide(), markFresh);
        if (remaining.signum() <= 0
                || (order.getTimeInForce() == TimeInForce.GTX
                        && !FuturesArrival.sweep(book, order.getSide(), remaining, request.price())
                                .isEmpty())) {
            // The exchange cancels rather than amends: nothing would be left to wait, or a post-only order would take.
            cancelWorking(account, order, now);
            return PaperResponses.futuresOrder(order, pair.symbol());
        }
        BigDecimal mark = market.currentMarkPrice(pair.symbol()).orElse(null);
        BigDecimal last =
                market.currentLastPrice(MarketType.FUTURES, pair.symbol()).orElse(mark);
        boolean reducing = order.reducing();
        if (!reducing) {
            SpotFilters.requireNotional(filters, request.price().multiply(request.quantity()), false, "quantity");
        }
        BigDecimal size = Optional.ofNullable(margins.openPosition(userId, pair.pairId(), order.getPositionSide()))
                .map(FuturesPosition::signedQuantity)
                .orElse(BigDecimal.ZERO);
        if (order.closesHedgeSide() && remaining.compareTo(FuturesSettlement.closableBy(order.getSide(), size)) > 0) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_REDUCE_ONLY_REJECTED, -2022, "ReduceOnly Order is rejected.", "reduceOnly");
        }
        BigDecimal opening = reducing ? BigDecimal.ZERO : opening(order.getSide(), remaining, size);
        // A stale mark is told first, as on placement.
        if (opening.signum() > 0) {
            requireFreshMarks(userId, pair, order.getSide(), request.price(), markFresh);
        }
        requirePercentPrice(
                filters, order.getSide(), request.price(), percentPriceMark(mark, markFresh, opening), book);
        if (opening.signum() > 0) {
            requireMargin(userId, pair, order.getSide(), request.price(), size, opening, last, mark, order.getId());
        }
        if (order.amend(request.price(), request.quantity(), now)) {
            // It waits again at its new price, as a new order would: the engine drops the copy it tracks, and the
            // order takes what the book now offers at that price, as a taker, before it waits.
            events.publishEvent(new PaperOrderCanceled(order.getId()));
            futuresArrival.limit(account, order, book, now);
            tpSl.settle(account, order, now);
        }
        feed.futuresOrder(account, order);
        log.debug(
                "Paper Futures order {} {} amended to {} at {}: {}",
                order.getId(),
                pair.symbol(),
                order.getOrigQuantity(),
                order.getLimitPrice(),
                order.getOrderStatus());
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
    public List<UUID> dueGoodTill(Instant at) {
        return orders
                .findByGoodTillDateLessThanEqualAndOrderStatusInOrderByGoodTillDateAsc(at, OrderStatus.OPEN)
                .stream()
                .map(PaperOrder::getId)
                .toList();
    }

    @Override
    @Transactional
    public boolean expireGoodTill(UUID orderId, Instant at) {
        Optional<PaperOrder> due = orders.findById(orderId);
        if (due.isEmpty()) {
            return false;
        }
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, due.get().getUserId());
        // Read again under the lock: a fill or a cancel committed meanwhile is seen. findById alone would answer the
        // instance already held by this transaction.
        PaperOrder order = due.get();
        entityManager.refresh(order);
        if (!order.expiredBy(at)) {
            return false;
        }
        PaperAccount account = accounts.findByUserId(order.getUserId())
                .orElseThrow(() -> new IllegalStateException("account " + order.getUserId() + " is not stored"));
        order.expire(GOOD_TILL_DATE, at);
        feed.futuresOrder(account, order);
        events.publishEvent(new PaperOrderCanceled(order.getId()));
        tpSl.settle(account, order, at);
        return true;
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
    public List<FuturesOrderResponse> forceOrders(
            UUID userId, String symbol, Instant startTime, Instant endTime, Integer limit) {
        requireFutures(userId);
        TimeBounds.requireOrdered(startTime, endTime);
        UUID pairId = symbol == null ? null : margins.pair(symbol).pairId();
        int size = forceLimitOf(limit);
        Instant from = TimeBounds.fromOrEarliest(startTime);
        Instant to = TimeBounds.toOrLatest(endTime);
        PageRequest page = PageRequest.of(0, MAX_LIMIT);
        List<PaperOrder> found = (pairId == null
                        ? orders.search(userId, MarketType.FUTURES, EnumSet.allOf(OrderStatus.class), from, to, page)
                        : orders.searchPair(
                                userId, pairId, MarketType.FUTURES, EnumSet.allOf(OrderStatus.class), from, to, page))
                .getContent().stream()
                        .filter(order -> order.getClientOrderId().startsWith(FuturesLiquidation.AUTOCLOSE))
                        .limit(size)
                        .toList();
        Map<UUID, String> symbols =
                symbolsOf(found.stream().map(PaperOrder::getPairId).toList());
        return found.stream()
                .map(order -> PaperResponses.futuresOrder(order, symbols.get(order.getPairId())))
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
        PaperOrder order = stored(
                request,
                PaperOrder.futuresMarket(
                        account.userId(),
                        pair.pairId(),
                        key,
                        request.side(),
                        positionSide,
                        request.quantity(),
                        request.reducing()));
        Instant now = clock.instant();
        futuresArrival.market(account, order, book, now);
        tpSl.settle(account, order, now);
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
        if (timeInForce.immediate() && book.isEmpty()) {
            throw priceUnavailable(pair);
        }
        PaperOrder order = stored(
                request,
                goodTill(
                        PaperOrder.futuresLimit(
                                account.userId(),
                                pair.pairId(),
                                key,
                                request.side(),
                                positionSide,
                                timeInForce,
                                request.price(),
                                request.quantity(),
                                request.reducing()),
                        request.goodTillDate()));
        Instant now = clock.instant();
        futuresArrival.limit(account, order, book, now);
        tpSl.settle(account, order, now);
        return order;
    }

    /** A GTD order with its date; any other unchanged. */
    private static PaperOrder goodTill(PaperOrder order, Instant goodTillDate) {
        return goodTillDate == null ? order : order.goodTill(goodTillDate);
    }

    /**
     * A GTD order's date is more than 600 seconds ahead and before the year 10000, as the exchange takes it
     * ({@code -1130}).
     */
    private void requireGoodTillDate(Instant goodTillDate) {
        if (goodTillDate != null
                && (!goodTillDate.isAfter(clock.instant().plus(GOOD_TILL_MIN_AHEAD))
                        || goodTillDate.isAfter(GOOD_TILL_LATEST))) {
            throw new FieldValidationException(
                    "goodTillDate must be more than 600 seconds ahead and before the year 10000",
                    Map.of("goodTillDate", "MSG15"));
        }
    }

    /** Stores a new order, grouped first with the take profit and stop loss it comes with, if any (plan 5.6). */
    private PaperOrder stored(FuturesOrderRequest request, PaperOrder order) {
        if (request.withTpSl()) {
            tpSl.attach(
                    order,
                    request.takeProfitPrice(),
                    request.stopLossPrice(),
                    "MARK_PRICE".equals(request.tpSlWorkingType()) ? WorkingType.MARK_PRICE : WorkingType.LAST_PRICE);
        }
        return orders.save(order);
    }

    /**
     * The part of an order that would open or increase a position must be paid for, and the position after it must
     * stay within the leverage's largest notional (4.6 … 4.8). An amended order is costed against what the account has
     * without its own margin before the amendment, {@code excludedOrderId}; a new one passes {@code null}.
     */
    private void requireMargin(
            UUID userId,
            FuturesPair pair,
            OrderSide side,
            BigDecimal price,
            BigDecimal size,
            BigDecimal opening,
            BigDecimal last,
            BigDecimal mark,
            UUID excludedOrderId) {
        if (mark == null || last == null) {
            throw priceUnavailable(pair);
        }
        FuturesSymbolConfig config = margins.symbolConfig(userId, pair.pairId(), pair.tiers());
        int leverage = config.getLeverage();
        BigDecimal held =
                PositionDirection.openedBy(side).sign().signum() == size.signum() ? size.abs() : BigDecimal.ZERO;
        BigDecimal valuedAt = price != null ? price : mark;
        BigDecimal notional = held.add(opening).multiply(valuedAt);
        if (notional.compareTo(FuturesMarginMath.maxNotionalAtLeverage(pair.tiers(), leverage)) > 0) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_MAX_POSITION_AT_LEVERAGE,
                    -2027,
                    "Exceeded the maximum allowable position at current leverage.");
        }
        BigDecimal cost = FuturesMarginMath.orderCost(side, opening, price, last, mark, leverage);
        FuturesAccountMargin margin =
                margins.crossState(userId, excludedOrderId).margin();
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
    private void requireFreshMarks(UUID userId, FuturesPair pair, OrderSide side, BigDecimal price, boolean markFresh) {
        if (!markFresh && futuresArrival.anyPriceReaches(pair.pairId(), pair.symbol(), side, price)) {
            throw FuturesMargins.staleMark(pair.symbol());
        }
        margins.requireFreshCrossMarks(userId, pair.pairId());
    }

    /**
     * A take profit and a stop loss on the pair's tick and on their own side of the order's price: above it for the
     * take profit of a long and below for its stop loss, the other way round for a short; otherwise they would trigger
     * as soon as the order executed.
     */
    private static void requireTpSl(PairFilters filters, FuturesOrderRequest request, BigDecimal reference) {
        boolean buy = request.side() == OrderSide.BUY;
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.takeProfitPrice() != null) {
            SpotFilters.requirePrice(filters, request.takeProfitPrice(), "takeProfitPrice");
            if (reference != null && wrongSide(request.takeProfitPrice().compareTo(reference), buy)) {
                errors.put("takeProfitPrice", "MSG15");
            }
        }
        if (request.stopLossPrice() != null) {
            SpotFilters.requirePrice(filters, request.stopLossPrice(), "stopLossPrice");
            if (reference != null && wrongSide(request.stopLossPrice().compareTo(reference), !buy)) {
                errors.put("stopLossPrice", "MSG15");
            }
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException("a take profit or stop loss on the wrong side of the order", errors);
        }
    }

    /**
     * Whether a price compared with the order's price is on the wrong side of it.
     *
     * @param above whether the price must be above the order's price
     */
    private static boolean wrongSide(int comparison, boolean above) {
        return above ? comparison <= 0 : comparison >= 0;
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
        tpSl.settle(account, order, at);
    }

    /**
     * The mark PERCENT_PRICE judges an order at: the current one. On a stale pair, an order that opens risk is still
     * held to the last mark known, the only bound there is, so that one resting far from the market cannot later fill
     * at its own price; one that only closes is judged by nothing, as a stale mark decides nothing for it (8.2.1 rule
     * 2).
     */
    private static BigDecimal percentPriceMark(BigDecimal mark, boolean markFresh, BigDecimal opening) {
        return markFresh || opening.signum() > 0 ? mark : null;
    }

    /**
     * {@code PERCENT_PRICE} of Futures (TR-39, plan 5.1): a BUY at most the mark times {@code multiplierUp}
     * ({@code -4016}), a SELL at least the mark times {@code multiplierDown} ({@code -4024}); a MARKET order is judged
     * by the best price of the book it takes ({@code -4131}). Without a mark, or a book for a MARKET order, nothing is
     * checked: the order is then refused or valued by the stale-mark rules.
     *
     * @param price the limit price, or {@code null} for a MARKET order
     */
    private static void requirePercentPrice(
            PairFilters filters, OrderSide side, BigDecimal price, BigDecimal mark, Optional<SpotArrival.Book> book) {
        boolean marketOrder = price == null;
        BigDecimal judged = marketOrder
                ? book.map(found -> found.levels().getFirst().price()).orElse(null)
                : price;
        if (withinPercentPrice(filters, side, judged, mark)) {
            return;
        }
        boolean buy = side == OrderSide.BUY;
        String limit = mark.multiply(
                        buy
                                ? filters.rules().bidMultiplierUp()
                                : filters.rules().askMultiplierDown())
                .stripTrailingZeros()
                .toPlainString();
        throw marketOrder
                ? new FuturesRejection(
                        ErrorCode.PAPER_PERCENT_PRICE,
                        -4131,
                        "The counterparty's best price does not meet the PERCENT_PRICE filter limit.",
                        "price",
                        judged,
                        limit,
                        buy ? SpotFilters.MAX : SpotFilters.MIN)
                : new FuturesRejection(
                        ErrorCode.PAPER_PERCENT_PRICE,
                        buy ? -4016 : -4024,
                        "Limit price can't be " + (buy ? "higher" : "lower") + " than " + limit + ".",
                        "price",
                        judged,
                        limit,
                        buy ? SpotFilters.MAX : SpotFilters.MIN);
    }

    /**
     * Whether a price of {@code side} meets PERCENT_PRICE: a BUY at most the mark times {@code multiplierUp}, a SELL at
     * least the mark times {@code multiplierDown}. Met without a mark, a multiplier or a price to judge.
     *
     * @param judged the limit price, or the best price of the book a MARKET order takes
     */
    static boolean withinPercentPrice(PairFilters filters, OrderSide side, BigDecimal judged, BigDecimal mark) {
        boolean buy = side == OrderSide.BUY;
        BigDecimal multiplier =
                buy ? filters.rules().bidMultiplierUp() : filters.rules().askMultiplierDown();
        if (mark == null || multiplier == null || judged == null) {
            return true;
        }
        BigDecimal bound = mark.multiply(multiplier);
        return buy ? judged.compareTo(bound) <= 0 : judged.compareTo(bound) >= 0;
    }

    /** Whether an order of {@code side} on {@code positionSide} closes a hedge-mode side (TR-14b). */
    static boolean closesHedgeSide(PositionSide positionSide, OrderSide side) {
        return (positionSide == PositionSide.LONG && side == OrderSide.SELL)
                || (positionSide == PositionSide.SHORT && side == OrderSide.BUY);
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
    static void requireShape(FuturesOrderRequest request) {
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
        if (request.timeInForce() == TimeInForce.GTD && request.goodTillDate() == null) {
            // The exchange's -1102.
            errors.put("goodTillDate", "MSG01");
        }
        if (request.timeInForce() != TimeInForce.GTD && request.goodTillDate() != null) {
            errors.put("goodTillDate", "MSG15");
        }
        if (request.withTpSl() && request.reducing()) {
            // A take profit or stop loss comes with an order that opens a position.
            errors.put("reduceOnly", "MSG15");
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

    /** An opened paper account (MSG41); Futures is open to every plan, as Spot. */
    private PaperAccount requireFutures(UUID userId) {
        return accounts.findByUserId(userId).orElseThrow(() -> new ResourceNotFoundException("PaperAccount", userId));
    }

    private Map<UUID, String> symbolsOf(List<UUID> pairIds) {
        Map<UUID, String> symbols = new LinkedHashMap<>();
        if (!pairIds.isEmpty()) {
            market.pairListings(Set.copyOf(pairIds)).forEach(pair -> symbols.put(pair.pairId(), pair.symbol()));
        }
        return symbols;
    }

    private static int forceLimitOf(Integer limit) {
        if (limit == null) {
            return 50;
        }
        if (limit < 1 || limit > 100) {
            throw new FieldValidationException("limit must be 1 to 100", Map.of("limit", "MSG15"));
        }
        return limit;
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
