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
import com.cryptopilot.paper.config.PaperFuturesProperties;
import com.cryptopilot.paper.dto.request.FuturesAlgoOrderRequest;
import com.cryptopilot.paper.dto.response.FuturesAlgoOrderResponse;
import com.cryptopilot.paper.entity.FuturesAccountConfig;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.exception.FuturesRejection;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.model.enums.TimeInForce;
import com.cryptopilot.paper.model.enums.TriggerCondition;
import com.cryptopilot.paper.model.enums.WorkingType;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import com.cryptopilot.paper.service.FuturesAlgoService;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The paper Futures conditional orders of TR-20, one-way mode, as the exchange takes them
 * ({@code POST /fapi/v1/algoOrder}, plan 5.4), trailing stops included (TR-21):
 *
 * <ul>
 *   <li>The triggerPrice and price are checked against the pair's PRICE_FILTER, the quantity against LOT_SIZE (and
 *       MARKET_LOT_SIZE for the market kinds), and refused, never rounded.
 *   <li>One that would trigger at once on the price it watches is refused ({@code -2021}); without that price it
 *       cannot be judged and is refused too ({@code MARKET_PRICE_UNAVAILABLE}).
 *   <li>The open conditional orders of the pair are at most {@code MAX_NUM_ALGO_ORDERS}, and every open order at most
 *       {@code MAX_NUM_ORDERS}.
 *   <li>No margin is checked now: the exchange checks it when the order triggers ({@link FuturesTrigger}).
 * </ul>
 *
 * <p>Once stored, the engine watches the order on the last price or on the mark; a cancel drops it. Every change runs
 * under the Trader's paper lock.
 *
 * <p>Rule: TR-20, TR-21; D-127.
 * <p>Reference: Binance. <i>USDⓈ-M Futures API: New Algo Order, Cancel Algo Order, Cancel All Algo Open Orders, Query
 * Algo Order, Current All Algo Open Orders, Query All Algo Orders</i>, Binance Developer Center.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesAlgoServiceImpl implements FuturesAlgoService {

    /** The most open conditional orders of an account on one pair when the exchange sent no MAX_NUM_ALGO_ORDERS. */
    static final int DEFAULT_MAX_NUM_ALGO_ORDERS = 10;

    /** The smallest callback of a trailing stop, in percent (plan 5.5). */
    private static final BigDecimal MIN_CALLBACK_RATE = new BigDecimal("0.1");

    private static final int DEFAULT_LIMIT = 500;

    private static final int MAX_LIMIT = 1000;

    private final PaperAccountRepository accounts;
    private final PaperOrderRepository orders;
    private final FuturesMargins margins;
    private final UserLock lock;
    private final MarketApi market;
    private final ApplicationEventPublisher events;
    private final FuturesOrderLists tpSl;
    private final PaperUserFeed feed;
    private final Clock clock;
    private final PaperFuturesProperties futures;

    @Override
    @Transactional
    public FuturesAlgoOrderResponse place(UUID userId, FuturesAlgoOrderRequest request) {
        PaperAccount account = requireFutures(userId);
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        FuturesAccountConfig accountConfig = margins.accountConfig(userId);
        if (accountConfig.liquidating()) {
            throw new FuturesRejection(ErrorCode.PAPER_USER_IN_LIQUIDATION, -2023, "User in liquidation mode now.");
        }
        requireShape(request);
        PositionSide positionSide =
                FuturesMargins.positionSide(accountConfig, request.positionSide(), request.reduceOnly() != null);
        if (request.closing()
                && positionSide != PositionSide.BOTH
                && !FuturesOrderServiceImpl.closesHedgeSide(positionSide, request.side())) {
            // In hedge mode a closePosition order closes its side: SELL on LONG or BUY on SHORT (plan 5.4).
            throw new FuturesRejection(
                    ErrorCode.PAPER_POSITION_SIDE,
                    -4061,
                    "Order's position side does not match user's setting.",
                    "positionSide",
                    positionSide);
        }
        if (request.clientAlgoId() != null) {
            Optional<PaperOrder> placed = orders.findByUserIdAndClientOrderId(userId, request.clientAlgoId());
            if (placed.isPresent()) {
                return replayOf(placed.get(), request);
            }
        }
        FuturesPair pair = margins.pair(request.symbol());
        PairFilters filters = pair.filters();
        boolean marketKind = request.type().isFuturesMarketConditional();
        boolean trailing = request.type() == OrderType.TRAILING_STOP_MARKET;
        if (trailing) {
            if (request.activatePrice() != null) {
                SpotFilters.requirePrice(filters, request.activatePrice(), "activatePrice");
            }
        } else {
            SpotFilters.requirePrice(filters, request.triggerPrice(), "triggerPrice");
        }
        if (!marketKind) {
            SpotFilters.requirePrice(filters, request.price(), "price");
        }
        if (request.quantity() != null) {
            SpotFilters.requireQuantity(filters, request.quantity(), "quantity");
            if (marketKind) {
                SpotFilters.requireMarketQuantity(filters, request.quantity(), "quantity", true);
            }
        }
        requireRoom(userId, pair);
        WorkingType workingType =
                "MARK_PRICE".equals(request.workingType()) ? WorkingType.MARK_PRICE : WorkingType.LAST_PRICE;
        requireNotTriggered(pair, request, workingType);
        PaperOrder order = orders.save(
                trailing
                        ? PaperOrder.futuresTrailing(
                                userId,
                                pair.pairId(),
                                request.clientAlgoId() == null ? UuidV7.next().toString() : request.clientAlgoId(),
                                request.side(),
                                positionSide,
                                request.activatePrice(),
                                request.callbackRate(),
                                request.quantity(),
                                workingType,
                                request.reducing())
                        : PaperOrder.futuresConditional(
                                userId,
                                pair.pairId(),
                                request.clientAlgoId() == null ? UuidV7.next().toString() : request.clientAlgoId(),
                                request.type(),
                                request.side(),
                                positionSide,
                                marketKind
                                        ? null
                                        : request.timeInForce() == null ? TimeInForce.GTC : request.timeInForce(),
                                request.triggerPrice(),
                                marketKind ? null : request.price(),
                                request.closing() ? null : request.quantity(),
                                workingType,
                                request.protecting(),
                                request.reducing(),
                                request.closing()));
        events.publishEvent(FuturesTrigger.awaiting(order));
        feed.futuresOrder(account, order);
        return PaperResponses.futuresAlgo(order, pair.symbol(), null);
    }

    @Override
    @Transactional
    public FuturesAlgoOrderResponse cancel(UUID userId, String symbol, UUID algoId, String clientAlgoId) {
        PaperAccount account = requireFutures(userId);
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        FuturesPair pair = margins.pair(symbol);
        PaperOrder order = find(userId, pair, algoId, clientAlgoId);
        if (!order.isOpen()) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_ORDER_NOT_OPEN, -2011, "Unknown order sent.", order.getOrderStatus());
        }
        cancelWaiting(account, order, clock.instant());
        return PaperResponses.futuresAlgo(order, pair.symbol(), null);
    }

    @Override
    @Transactional
    public List<FuturesAlgoOrderResponse> cancelAll(UUID userId, String symbol) {
        PaperAccount account = requireFutures(userId);
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        FuturesPair pair = margins.pair(symbol);
        List<PaperOrder> open = margins.openConditionalOrders(userId).stream()
                .filter(order -> order.getPairId().equals(pair.pairId()))
                .toList();
        Instant now = clock.instant();
        open.forEach(order -> cancelWaiting(account, order, now));
        return open.stream()
                .map(order -> PaperResponses.futuresAlgo(order, pair.symbol(), null))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public FuturesAlgoOrderResponse order(UUID userId, String symbol, UUID algoId, String clientAlgoId) {
        requireFutures(userId);
        FuturesPair pair = margins.pair(symbol);
        PaperOrder order = find(userId, pair, algoId, clientAlgoId);
        return PaperResponses.futuresAlgo(
                order, pair.symbol(), orders.findByParentOrderId(order.getId()).orElse(null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FuturesAlgoOrderResponse> openOrders(UUID userId, String symbol) {
        requireFutures(userId);
        UUID pairId = symbol == null ? null : margins.pair(symbol).pairId();
        List<PaperOrder> open = margins.openConditionalOrders(userId).stream()
                .filter(order -> pairId == null || order.getPairId().equals(pairId))
                .toList();
        Map<UUID, String> symbols = new LinkedHashMap<>();
        if (!open.isEmpty()) {
            market.pairListings(open.stream().map(PaperOrder::getPairId).collect(Collectors.toSet()))
                    .forEach(pair -> symbols.put(pair.pairId(), pair.symbol()));
        }
        return open.stream()
                .map(order -> PaperResponses.futuresAlgo(order, symbols.get(order.getPairId()), null))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FuturesAlgoOrderResponse> allOrders(
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
        found.removeIf(order -> !order.isConditional());
        Collections.reverse(found);
        Map<UUID, PaperOrder> placed =
                orders
                        .findByParentOrderIdIn(
                                found.stream().map(PaperOrder::getId).toList())
                        .stream()
                        .collect(Collectors.toMap(PaperOrder::getParentOrderId, Function.identity()));
        return found.stream()
                .map(order -> PaperResponses.futuresAlgo(order, pair.symbol(), placed.get(order.getId())))
                .toList();
    }

    /**
     * Whether the price the order watches already meets its condition (plan 5.4): then it would trigger at once and
     * is refused, as the exchange refuses it.
     */
    private void requireNotTriggered(FuturesPair pair, FuturesAlgoOrderRequest request, WorkingType workingType) {
        BigDecimal trigger =
                request.type() == OrderType.TRAILING_STOP_MARKET ? request.activatePrice() : request.triggerPrice();
        if (trigger == null) {
            // A trailing stop without an activation starts at the next price.
            return;
        }
        Optional<BigDecimal> latest = workingType == WorkingType.MARK_PRICE
                ? margins.freshMark(pair.symbol())
                : market.currentLastPrice(MarketType.FUTURES, pair.symbol());
        BigDecimal price = latest.orElseThrow(() -> new BusinessException(
                ErrorCode.MARKET_PRICE_UNAVAILABLE,
                "no current " + workingType.binanceName() + " of " + pair.symbol() + " to judge the trigger"));
        TriggerCondition condition = request.type().futuresTriggerConditionFor(request.side());
        int side = price.compareTo(trigger);
        boolean met = condition == TriggerCondition.AT_OR_ABOVE ? side >= 0 : side <= 0;
        if (met) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_ORDER_WOULD_TRIGGER, -2021, "Order would immediately trigger.", "triggerPrice");
        }
    }

    /** MAX_NUM_ALGO_ORDERS of the pair's conditional orders, and MAX_NUM_ORDERS of every open order of it. */
    private void requireRoom(UUID userId, FuturesPair pair) {
        Integer maxAlgo = pair.filters().rules().maxNumAlgoOrders();
        int algoLimit = maxAlgo != null ? maxAlgo : DEFAULT_MAX_NUM_ALGO_ORDERS;
        long algos = margins.openConditionalOrders(userId).stream()
                .filter(order -> order.getPairId().equals(pair.pairId()))
                .count();
        if (algos + 1 > algoLimit) {
            throw new FuturesRejection(
                    ErrorCode.PAPER_MAX_NUM_ALGO_ORDERS,
                    -4045,
                    "Reach max stop order limit.",
                    "algoOrders",
                    algos + 1,
                    algoLimit,
                    SpotFilters.MAX);
        }
        Integer max = pair.filters().rules().maxNumOrders();
        int limit = max != null ? max : FuturesOrderServiceImpl.DEFAULT_MAX_NUM_ORDERS;
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

    /** Which fields the type takes, before the pair is looked up. */
    private void requireShape(FuturesAlgoOrderRequest request) {
        BigDecimal maxCallbackRate = futures.maxCallbackRate();
        Map<String, String> errors = new LinkedHashMap<>();
        Set<OrderType> taken = OrderType.FUTURES_CONDITIONAL;
        boolean trailing = request.type() == OrderType.TRAILING_STOP_MARKET;
        if (!taken.contains(request.type())) {
            errors.put("type", "MSG15");
        } else if (trailing) {
            if (request.callbackRate() == null) {
                errors.put("callbackRate", "MSG01");
            } else if (request.callbackRate().compareTo(MIN_CALLBACK_RATE) < 0
                    || request.callbackRate().compareTo(maxCallbackRate) > 0) {
                errors.put("callbackRate", "MSG15");
            }
            if (request.triggerPrice() != null) {
                errors.put("triggerPrice", "MSG15");
            }
            if (request.quantity() == null) {
                errors.put("quantity", "MSG01");
            }
            if (request.closing()) {
                errors.put("closePosition", "MSG15");
            }
            if (request.price() != null) {
                errors.put("price", "MSG15");
            }
            if (request.timeInForce() != null) {
                errors.put("timeInForce", "MSG15");
            }
        } else {
            if (request.triggerPrice() == null) {
                errors.put("triggerPrice", "MSG01");
            }
            boolean marketKind = request.type().isFuturesMarketConditional();
            if (marketKind && request.price() != null) {
                errors.put("price", "MSG15");
            }
            if ((marketKind && request.timeInForce() != null) || request.timeInForce() == TimeInForce.GTD) {
                // GTD is taken by a LIMIT order only, which carries its date (TR-44).
                errors.put("timeInForce", "MSG15");
            }
            if (!marketKind && request.price() == null) {
                errors.put("price", "MSG01");
            }
            if (!marketKind && request.closing()) {
                errors.put("closePosition", "MSG15");
            }
            if (request.closing() && request.quantity() != null) {
                // The exchange's -4137.
                errors.put("quantity", "MSG15");
            }
            if (request.closing() && request.reduceOnly() != null) {
                // The exchange's -1106.
                errors.put("reduceOnly", "MSG15");
            }
            if (!request.closing() && request.quantity() == null) {
                errors.put("quantity", "MSG01");
            }
        }
        if (!trailing && request.activatePrice() != null) {
            errors.put("activatePrice", "MSG15");
        }
        if (!trailing && request.callbackRate() != null) {
            errors.put("callbackRate", "MSG15");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(
                    "FUTURES " + request.type() + " conditional order with fields that do not fit it", errors);
        }
    }

    /** The order a retry asks for again; a key reused for a different order is a client error. */
    private FuturesAlgoOrderResponse replayOf(PaperOrder placed, FuturesAlgoOrderRequest request) {
        FuturesPair pair = margins.pair(request.symbol());
        boolean same = placed.getMarketType() == MarketType.FUTURES
                && placed.isConditional()
                && placed.getPairId().equals(pair.pairId())
                && placed.getSide() == request.side()
                && placed.getOrderType() == request.type()
                && placed.isClosePosition() == request.closing()
                && sameValue(
                        placed.getStopPrice(),
                        request.type() == OrderType.TRAILING_STOP_MARKET
                                ? request.activatePrice()
                                : request.triggerPrice())
                && sameValue(placed.getCallbackRate(), request.callbackRate())
                && sameValue(placed.getLimitPrice(), request.price())
                && sameValue(placed.getOrigQuantity(), request.quantity());
        if (!same) {
            throw new BusinessException(
                    ErrorCode.DATA_CONFLICT,
                    "clientAlgoId " + request.clientAlgoId() + " was used for order " + placed.getId()
                            + " of other values");
        }
        return PaperResponses.futuresAlgo(
                placed,
                pair.symbol(),
                orders.findByParentOrderId(placed.getId()).orElse(null));
    }

    private PaperOrder find(UUID userId, FuturesPair pair, UUID algoId, String clientAlgoId) {
        if (algoId == null && clientAlgoId == null) {
            throw new FieldValidationException("either algoId or clientAlgoId must be sent", Map.of("algoId", "MSG01"));
        }
        Optional<PaperOrder> found = algoId != null
                ? orders.findByIdAndUserIdAndMarketType(algoId, userId, MarketType.FUTURES)
                : orders.findByUserIdAndClientOrderIdAndMarketType(userId, clientAlgoId, MarketType.FUTURES);
        return found.filter(order -> order.getPairId().equals(pair.pairId()) && order.isConditional())
                .orElseThrow(() -> new ResourceNotFoundException("PaperOrder", algoId != null ? algoId : clientAlgoId));
    }

    /** Cancels a waiting conditional order; the engine stops watching it after the commit. */
    private void cancelWaiting(PaperAccount account, PaperOrder order, Instant at) {
        order.cancel(at);
        feed.futuresOrder(account, order);
        events.publishEvent(new PaperOrderCanceled(order.getId()));
        tpSl.settle(account, order, at);
    }

    /** An opened paper account (MSG41); Futures is open to every plan, as Spot. */
    private PaperAccount requireFutures(UUID userId) {
        return accounts.findByUserId(userId).orElseThrow(() -> new ResourceNotFoundException("PaperAccount", userId));
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
}
