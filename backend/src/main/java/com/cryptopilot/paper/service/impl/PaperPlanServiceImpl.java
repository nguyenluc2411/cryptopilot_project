package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.config.PaperOrderProperties;
import com.cryptopilot.paper.dto.request.FuturesAlgoOrderRequest;
import com.cryptopilot.paper.dto.request.FuturesLeverageRequest;
import com.cryptopilot.paper.dto.request.FuturesMarginTypeRequest;
import com.cryptopilot.paper.dto.request.FuturesOrderRequest;
import com.cryptopilot.paper.dto.request.PlaceOrderListRequest;
import com.cryptopilot.paper.dto.request.PlaceOrderRequest;
import com.cryptopilot.paper.dto.response.FuturesAlgoOrderResponse;
import com.cryptopilot.paper.dto.response.FuturesOrderResponse;
import com.cryptopilot.paper.dto.response.FuturesPositionRiskResponse;
import com.cryptopilot.paper.dto.response.FuturesSymbolConfigResponse;
import com.cryptopilot.paper.dto.response.OrderListResponse;
import com.cryptopilot.paper.dto.response.OrderResponse;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.entity.PlanOrderScope;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderListType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TimeInForce;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.FuturesPositionRepository;
import com.cryptopilot.paper.repository.PaperBalanceRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import com.cryptopilot.paper.service.FuturesAccountService;
import com.cryptopilot.paper.service.FuturesAlgoService;
import com.cryptopilot.paper.service.FuturesConfigService;
import com.cryptopilot.paper.service.FuturesOrderService;
import com.cryptopilot.paper.service.PaperOrderService;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.PlanOrderPlacer;
import com.cryptopilot.trading.PlanOrderTerms;
import com.cryptopilot.trading.TradingPlanApi;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carries a plan out with paper orders (RF-33, D-117: one engine). Activating a plan places its orders through the
 * same paths as any other paper order, every order stored inside a {@link PlanOrderScope} so it names the plan. The
 * quantity, entry and levels are the plan's; the order paths check them against the pair's filters and the wallet as
 * for any order, and a refusal rolls the activation back.
 *
 * <ul>
 *   <li>Spot, MARKET entry: a MARKET buy, then the take profit and stop loss as an OCO sale of what it bought (only
 *       the stop loss when the plan has no take profit).
 *   <li>Spot, LIMIT entry: an OTOCO, the limit buy working and the OCO placed once it fills (an OTO with the stop loss
 *       when there is no take profit).
 *   <li>Spot, STOP entry: a STOP_LOSS buy at the entry; the levels are the Trader's to add once it fills.
 *   <li>Futures: the plan's leverage and isolated margin on the pair, then a MARKET or LIMIT order with its take profit
 *       and stop loss; a STOP entry is a STOP_MARKET conditional order without them.
 * </ul>
 *
 * <p>Rule: BR-33, BR-68, BR-71; D-117; RF-33.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperPlanServiceImpl implements PlanOrderPlacer {

    private static final Logger log = LoggerFactory.getLogger(PaperPlanServiceImpl.class);

    /** The statuses of an order that still works or waits to be placed. */
    static final Set<OrderStatus> LIVE = Set.of(OrderStatus.NEW, OrderStatus.PENDING_NEW, OrderStatus.PARTIALLY_FILLED);

    private final PaperOrderService spot;
    private final FuturesOrderService futures;
    private final FuturesAlgoService algos;
    private final FuturesConfigService configs;
    private final PaperOrderRepository orders;
    private final PaperOrderProperties fees;
    private final MarketApi market;
    private final TradingPlanApi plans;
    private final FuturesAccountService accounts;
    private final PaperBalanceRepository balances;
    private final FuturesPositionRepository positions;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void place(PlanOrderTerms terms) {
        UUID planId = terms.planId();
        if (orders.existsByPlanIdAndOrderStatusIn(planId, LIVE)) {
            throw new BusinessException(ErrorCode.PLAN_ALREADY_APPLIED, "plan " + planId + " has a working order");
        }
        TradablePair pair = market.tradablePair(terms.pairId(), terms.market())
                .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", terms.pairId()));
        Placed placed = PlanOrderScope.within(
                planId,
                () -> terms.market() == MarketType.SPOT
                        ? spot(terms.userId(), terms, pair)
                        : futures(terms.userId(), terms, pair));
        log.info(
                "RF-33 plan {} placed on {} {}: entry order {} {}, orders {}",
                planId,
                terms.market(),
                pair.symbol(),
                placed.entryOrderId(),
                placed.entryStatus(),
                placed.orderIds());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(UUID userId, UUID planId) {
        int cancelled = cancelLive(userId, planId);
        if (cancelled > 0) {
            log.info("RF-33 plan {} ended before its entry filled: {} order(s) cancelled", planId, cancelled);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PlanExitQuote quoteClose(UUID userId, OpenTrade trade) {
        TradablePair pair = pairOf(trade);
        BigDecimal last = lastPrice(pair);
        if (trade.market() == MarketType.SPOT) {
            BigDecimal quantity = sellable(userId, trade, pair, true);
            BigDecimal fee = Rounding.toAmount(last.multiply(quantity).multiply(fees.spotTakerFeeRate()));
            return new PlanExitQuote(last, last, quantity, fee, null);
        }
        BigDecimal quantity = openPosition(userId, pair).positionAmt().abs();
        BigDecimal rate = configs.commissionRate(userId, pair.symbol()).takerCommissionRate();
        BigDecimal fee = Rounding.toAmount(last.multiply(quantity).multiply(rate));
        return new PlanExitQuote(last, last, quantity, fee, fundingOf(userId, pair));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void close(UUID userId, OpenTrade trade) {
        TradablePair pair = pairOf(trade);
        // The take profit and stop loss hold the coin or would act on the position: they go first.
        cancelLive(userId, trade.planId());
        PlanOrderScope.within(trade.planId(), () -> {
            if (trade.market() == MarketType.SPOT) {
                BigDecimal quantity = sellable(userId, trade, pair, false);
                if (quantity.signum() <= 0) {
                    throw new BusinessException(
                            ErrorCode.PAPER_INSUFFICIENT_BALANCE,
                            "plan " + trade.planId() + " holds no " + pair.symbol() + " to sell",
                            pair.symbol());
                }
                spot.place(userId, marketSell(pair, quantity));
            } else {
                BigDecimal quantity = openPosition(userId, pair).positionAmt().abs();
                futures.place(userId, reduceAtMarket(pair, trade.direction(), quantity));
            }
            return null;
        });
        log.info("UC-21 plan {} position closed at the market on the demo account ({})", trade.planId(), pair.symbol());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceLevels(UUID userId, OpenTrade trade, BigDecimal stopLoss, BigDecimal takeProfit) {
        TradablePair pair = pairOf(trade);
        cancelLive(userId, trade.planId());
        PlanOrderScope.within(trade.planId(), () -> {
            if (trade.market() == MarketType.SPOT) {
                placeSpotLevels(userId, pair, sellable(userId, trade, pair, false), stopLoss, takeProfit);
            } else {
                OrderSide closing = trade.direction() == Direction.LONG ? OrderSide.SELL : OrderSide.BUY;
                if (stopLoss != null) {
                    algos.place(userId, closeAt(pair, closing, OrderType.STOP_MARKET, stopLoss));
                }
                if (takeProfit != null) {
                    algos.place(userId, closeAt(pair, closing, OrderType.TAKE_PROFIT_MARKET, takeProfit));
                }
            }
            return null;
        });
        log.info(
                "D-124 plan {} levels on the demo account: stop loss {}, take profit {}",
                trade.planId(),
                stopLoss,
                takeProfit);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> liquidationPrice(UUID userId, UUID planId) {
        return plans.terms(planId)
                .filter(terms -> terms.market() == MarketType.FUTURES)
                .flatMap(terms -> market.tradablePair(terms.pairId(), MarketType.FUTURES))
                .flatMap(pair -> accounts.positionRisk(userId, pair.symbol()).stream()
                        .filter(position -> position.positionAmt().signum() != 0)
                        .findFirst())
                .map(FuturesPositionRiskResponse::liquidationPrice)
                .filter(price -> price.signum() > 0);
    }

    /**
     * Cancels the plan's orders that still work, one at a time and read again each time: cancelling an entry also
     * ends the orders that wait on it (its group's legs, a Futures order's take profit and stop loss), and those must
     * not be cancelled twice.
     *
     * @return how many were cancelled
     */
    private int cancelLive(UUID userId, UUID planId) {
        Set<UUID> tried = new HashSet<>();
        Optional<PaperOrder> next;
        while ((next = nextLive(planId, tried)).isPresent()) {
            PaperOrder order = next.get();
            tried.add(order.getId());
            cancelOne(userId, order);
        }
        return tried.size();
    }

    private TradablePair pairOf(OpenTrade trade) {
        return market.tradablePair(trade.pairId(), trade.market())
                .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", trade.pairId()));
    }

    private BigDecimal lastPrice(TradablePair pair) {
        return market.currentLastPrice(pair.market(), pair.symbol())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.MARKET_PRICE_UNAVAILABLE,
                        "no current last price for " + pair.symbol() + " on " + pair.market()));
    }

    /**
     * What a Spot close sells: the coin the plan's position holds, at most what the wallet has of it, floored to the
     * pair's step. Before the take profit and stop loss are cancelled ({@code withLocked}) the coin they lock counts.
     */
    private BigDecimal sellable(UUID userId, OpenTrade trade, TradablePair pair, boolean withLocked) {
        UUID base = market.pairCoins(pair.pairId())
                .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", pair.pairId()))
                .base()
                .coinId();
        BigDecimal held = balances.findByUserIdAndWalletTypeAndCoinId(userId, WalletType.SPOT, base)
                .map(balance ->
                        withLocked ? balance.getFreeAmount().add(balance.getLockedAmount()) : balance.getFreeAmount())
                .orElse(BigDecimal.ZERO);
        BigDecimal step = pair.filters().stepSize();
        return trade.quantity()
                .min(held)
                .divide(step, 0, RoundingMode.DOWN)
                .multiply(step)
                .stripTrailingZeros();
    }

    private FuturesPositionRiskResponse openPosition(UUID userId, TradablePair pair) {
        return accounts.positionRisk(userId, pair.symbol()).stream()
                .filter(position -> position.positionAmt().signum() != 0)
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.TRADING_PLAN_STATUS_TRANSITION_INVALID,
                        "no open Futures position on " + pair.symbol()));
    }

    private BigDecimal fundingOf(UUID userId, TradablePair pair) {
        return positions
                .findByUserIdAndPairIdAndPositionStatus(
                        userId, pair.pairId(), com.cryptopilot.paper.model.enums.PositionStatus.OPEN)
                .stream()
                .findFirst()
                .map(FuturesPosition::getFundingAmount)
                .orElse(BigDecimal.ZERO);
    }

    /** The Spot take profit and stop loss of {@code quantity}: an OCO with both, else the one there is. */
    private void placeSpotLevels(
            UUID userId, TradablePair pair, BigDecimal quantity, BigDecimal stopLoss, BigDecimal takeProfit) {
        if (quantity.signum() <= 0 || stopLoss == null && takeProfit == null) {
            return;
        }
        if (stopLoss != null && takeProfit != null) {
            PlaceOrderListRequest.Oco oco = new PlaceOrderListRequest.Oco(
                    OrderSide.SELL, quantity, takeProfit, stopLoss, null, null, null, null);
            spot.placeList(
                    userId,
                    new PlaceOrderListRequest(
                            pair.pairId(), MarketType.SPOT, OrderListType.OCO, null, null, oco, null));
            return;
        }
        boolean stop = stopLoss != null;
        spot.place(
                userId,
                new PlaceOrderRequest(
                        pair.pairId(),
                        MarketType.SPOT,
                        OrderSide.SELL,
                        stop ? OrderType.STOP_LOSS : OrderType.LIMIT_MAKER,
                        null,
                        stop ? null : takeProfit,
                        quantity,
                        null,
                        null,
                        stop ? stopLoss : null,
                        null));
    }

    private static PlaceOrderRequest marketSell(TradablePair pair, BigDecimal quantity) {
        return new PlaceOrderRequest(
                pair.pairId(),
                MarketType.SPOT,
                OrderSide.SELL,
                OrderType.MARKET,
                null,
                null,
                quantity,
                null,
                null,
                null,
                null);
    }

    private static FuturesOrderRequest reduceAtMarket(TradablePair pair, Direction direction, BigDecimal quantity) {
        return new FuturesOrderRequest(
                pair.symbol(),
                direction == Direction.LONG ? OrderSide.SELL : OrderSide.BUY,
                null,
                OrderType.MARKET,
                null,
                quantity,
                null,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** A conditional order that closes the whole position when the mark price reaches {@code trigger}. */
    private static FuturesAlgoOrderRequest closeAt(
            TradablePair pair, OrderSide side, OrderType type, BigDecimal trigger) {
        return new FuturesAlgoOrderRequest(
                "CONDITIONAL",
                pair.symbol(),
                side,
                null,
                type,
                null,
                null,
                null,
                trigger,
                "MARK_PRICE",
                null,
                null,
                true,
                null,
                null,
                null);
    }

    private Optional<PaperOrder> nextLive(UUID planId, Set<UUID> tried) {
        // A Futures take profit or stop loss still PENDING_NEW waits on its entry and ends with it; a Spot one is
        // cancelled with its group.
        return orders.findByPlanIdOrderByCreatedAtAsc(planId).stream()
                .filter(order -> LIVE.contains(order.getOrderStatus()))
                .filter(order ->
                        order.isOpen() || order.getListId() != null && order.getMarketType() == MarketType.SPOT)
                .filter(order -> !tried.contains(order.getId()))
                .findFirst();
    }

    private void cancelOne(UUID userId, PaperOrder order) {
        if (order.getMarketType() == MarketType.SPOT) {
            if (order.getListId() != null) {
                spot.cancelList(userId, order.getListId());
            } else {
                spot.cancel(userId, order.getId());
            }
            return;
        }
        String symbol = market.tradablePair(order.getPairId(), MarketType.FUTURES)
                .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", order.getPairId()))
                .symbol();
        if (order.isConditional()) {
            algos.cancel(userId, symbol, order.getId(), null);
        } else {
            futures.cancel(userId, symbol, order.getId(), null);
        }
    }

    private Placed spot(UUID userId, PlanOrderTerms terms, TradablePair pair) {
        BigDecimal quantity = terms.quantity();
        if (terms.entryType() == EntryType.LIMIT) {
            PlaceOrderListRequest.Leg working =
                    new PlaceOrderListRequest.Leg(OrderSide.BUY, OrderType.LIMIT, terms.entryPrice(), null, quantity);
            PlaceOrderListRequest list = terms.takeProfit() == null
                    ? new PlaceOrderListRequest(
                            pair.pairId(),
                            MarketType.SPOT,
                            OrderListType.OTO,
                            working,
                            stopLossLeg(terms, sellable(quantity, pair)),
                            null,
                            null)
                    : new PlaceOrderListRequest(
                            pair.pairId(), MarketType.SPOT, OrderListType.OTOCO, working, null, oco(terms, null), null);
            return ofList(terms, pair, spot.placeList(userId, list).list());
        }
        boolean stop = terms.entryType() == EntryType.STOP;
        OrderResponse entry = spot.place(
                        userId,
                        new PlaceOrderRequest(
                                pair.pairId(),
                                MarketType.SPOT,
                                OrderSide.BUY,
                                stop ? OrderType.STOP_LOSS : OrderType.MARKET,
                                null,
                                null,
                                quantity,
                                null,
                                null,
                                stop ? terms.entryPrice() : null,
                                null))
                .order();
        List<UUID> ids = new ArrayList<>(List.of(entry.orderId()));
        if (!stop && entry.executedQuantity().signum() > 0) {
            BigDecimal bought = sellable(entry.executedQuantity(), pair);
            if (terms.takeProfit() == null) {
                ids.add(spot.place(userId, stopLossOrder(terms, pair, bought))
                        .order()
                        .orderId());
            } else {
                spot.placeList(
                                userId,
                                new PlaceOrderListRequest(
                                        pair.pairId(),
                                        MarketType.SPOT,
                                        OrderListType.OCO,
                                        null,
                                        null,
                                        oco(terms, bought),
                                        null))
                        .list()
                        .orders()
                        .forEach(order -> ids.add(order.orderId()));
            }
        }
        return new Placed(entry.orderId(), ids, entry.status().name());
    }

    private Placed futures(UUID userId, PlanOrderTerms terms, TradablePair pair) {
        String symbol = pair.symbol();
        useLeverageAndIsolatedMargin(userId, symbol, terms.leverage());
        OrderSide side = terms.direction() == Direction.LONG ? OrderSide.BUY : OrderSide.SELL;
        if (terms.entryType() == EntryType.STOP) {
            FuturesAlgoOrderResponse algo = algos.place(
                    userId,
                    new FuturesAlgoOrderRequest(
                            "CONDITIONAL",
                            symbol,
                            side,
                            null,
                            OrderType.STOP_MARKET,
                            null,
                            terms.quantity(),
                            null,
                            terms.entryPrice(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));
            return new Placed(algo.algoId(), List.of(algo.algoId()), algo.algoStatus());
        }
        boolean limit = terms.entryType() == EntryType.LIMIT;
        FuturesOrderResponse entry = futures.place(
                userId,
                new FuturesOrderRequest(
                        symbol,
                        side,
                        null,
                        limit ? OrderType.LIMIT : OrderType.MARKET,
                        limit ? TimeInForce.GTC : null,
                        terms.quantity(),
                        limit ? terms.entryPrice() : null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        terms.takeProfit(),
                        terms.stopLoss(),
                        "MARK_PRICE",
                        null));
        List<UUID> ids = new ArrayList<>(List.of(entry.orderId()));
        orders.findByPlanIdOrderByCreatedAtAsc(terms.planId()).stream()
                .map(order -> order.getId())
                .filter(id -> !ids.contains(id))
                .forEach(ids::add);
        return new Placed(entry.orderId(), ids, entry.status().name());
    }

    /** The plan's leverage and isolated margin on the pair, changed only where they differ (D-117). */
    private void useLeverageAndIsolatedMargin(UUID userId, String symbol, Integer leverage) {
        FuturesSymbolConfigResponse config =
                configs.symbolConfigs(userId, symbol).stream().findFirst().orElse(null);
        if (config == null || config.marginType() != MarginType.ISOLATED) {
            configs.changeMarginType(userId, new FuturesMarginTypeRequest(symbol, MarginType.ISOLATED));
        }
        if (leverage != null && (config == null || config.leverage() != leverage)) {
            configs.changeLeverage(userId, new FuturesLeverageRequest(symbol, leverage));
        }
    }

    private static Placed ofList(PlanOrderTerms terms, TradablePair pair, OrderListResponse list) {
        List<OrderResponse> placed = list.orders();
        OrderResponse entry = placed.getFirst();
        return new Placed(
                entry.orderId(),
                placed.stream().map(OrderResponse::orderId).toList(),
                entry.status().name());
    }

    /** The take profit (LIMIT_MAKER) and stop loss (STOP_LOSS) sale; {@code quantity} null in an OTOCO. */
    private static PlaceOrderListRequest.Oco oco(PlanOrderTerms terms, BigDecimal quantity) {
        return new PlaceOrderListRequest.Oco(
                OrderSide.SELL, quantity, terms.takeProfit(), terms.stopLoss(), null, null, null, null);
    }

    private static PlaceOrderListRequest.Leg stopLossLeg(PlanOrderTerms terms, BigDecimal quantity) {
        return new PlaceOrderListRequest.Leg(OrderSide.SELL, OrderType.STOP_LOSS, null, terms.stopLoss(), quantity);
    }

    private static PlaceOrderRequest stopLossOrder(PlanOrderTerms terms, TradablePair pair, BigDecimal quantity) {
        return new PlaceOrderRequest(
                pair.pairId(),
                MarketType.SPOT,
                OrderSide.SELL,
                OrderType.STOP_LOSS,
                null,
                null,
                quantity,
                null,
                null,
                terms.stopLoss(),
                null);
    }

    /**
     * What a Spot buy of {@code quantity} leaves to sell: less the larger Spot fee rate, which the buy takes from the
     * coin it brings (TR-13), floored to the pair's step.
     */
    private BigDecimal sellable(BigDecimal quantity, TradablePair pair) {
        BigDecimal rate = fees.spotMakerFeeRate().max(fees.spotTakerFeeRate());
        BigDecimal left = quantity.multiply(BigDecimal.ONE.subtract(rate));
        BigDecimal step = pair.filters().stepSize();
        return left.divide(step, 0, RoundingMode.DOWN).multiply(step).stripTrailingZeros();
    }

    /** What was placed, for the log. */
    private record Placed(UUID entryOrderId, List<UUID> orderIds, String entryStatus) {}
}
