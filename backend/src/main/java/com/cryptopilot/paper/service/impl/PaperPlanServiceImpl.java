package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
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
import com.cryptopilot.paper.dto.response.AppliedPlanResponse;
import com.cryptopilot.paper.dto.response.FuturesAlgoOrderResponse;
import com.cryptopilot.paper.dto.response.FuturesOrderResponse;
import com.cryptopilot.paper.dto.response.FuturesSymbolConfigResponse;
import com.cryptopilot.paper.dto.response.OrderListResponse;
import com.cryptopilot.paper.dto.response.OrderResponse;
import com.cryptopilot.paper.entity.PlanOrderScope;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderListType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TimeInForce;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import com.cryptopilot.paper.service.FuturesAlgoService;
import com.cryptopilot.paper.service.FuturesConfigService;
import com.cryptopilot.paper.service.FuturesOrderService;
import com.cryptopilot.paper.service.PaperOrderService;
import com.cryptopilot.paper.service.PaperPlanService;
import com.cryptopilot.trading.PlanOrderTerms;
import com.cryptopilot.trading.TradingPlanApi;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a plan through the paper order paths, every order stored inside a {@link PlanOrderScope} so it names the
 * plan. The quantity, entry and levels are the plan's; the order paths check them against the pair's filters and the
 * wallet as for any order, and a refusal rolls the whole application back.
 *
 * <p>Rule: BR-33, BR-71; D-117; RF-33.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperPlanServiceImpl implements PaperPlanService {

    private static final Logger log = LoggerFactory.getLogger(PaperPlanServiceImpl.class);

    /** The statuses of an order that still works or waits to be placed. */
    static final Set<OrderStatus> LIVE = Set.of(OrderStatus.NEW, OrderStatus.PENDING_NEW, OrderStatus.PARTIALLY_FILLED);

    private final TradingPlanApi plans;
    private final PaperOrderService spot;
    private final FuturesOrderService futures;
    private final FuturesAlgoService algos;
    private final FuturesConfigService configs;
    private final PaperOrderRepository orders;
    private final PaperOrderProperties fees;
    private final MarketApi market;

    @Override
    @Transactional
    public AppliedPlanResponse apply(UUID userId, UUID planId) {
        PlanOrderTerms terms = plans.termsToApply(userId, planId);
        if (orders.existsByPlanIdAndOrderStatusIn(planId, LIVE)) {
            throw new BusinessException(ErrorCode.PLAN_ALREADY_APPLIED, "plan " + planId + " has a working order");
        }
        TradablePair pair = market.tradablePair(terms.pairId(), terms.market())
                .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", terms.pairId()));
        AppliedPlanResponse applied = PlanOrderScope.within(
                planId,
                () -> terms.market() == MarketType.SPOT ? spot(userId, terms, pair) : futures(userId, terms, pair));
        log.info(
                "RF-33 plan {} applied on {} {}: entry order {} {}",
                planId,
                terms.market(),
                pair.symbol(),
                applied.entryOrderId(),
                applied.entryStatus());
        return applied;
    }

    private AppliedPlanResponse spot(UUID userId, PlanOrderTerms terms, TradablePair pair) {
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
        boolean protectedByStop = false;
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
            protectedByStop = true;
        }
        return new AppliedPlanResponse(
                terms.planId(),
                MarketType.SPOT,
                pair.symbol(),
                entry.orderId(),
                ids,
                entry.status().name(),
                protectedByStop);
    }

    private AppliedPlanResponse futures(UUID userId, PlanOrderTerms terms, TradablePair pair) {
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
            return new AppliedPlanResponse(
                    terms.planId(),
                    MarketType.FUTURES,
                    symbol,
                    algo.algoId(),
                    List.of(algo.algoId()),
                    algo.algoStatus(),
                    false);
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
        return new AppliedPlanResponse(
                terms.planId(),
                MarketType.FUTURES,
                symbol,
                entry.orderId(),
                ids,
                entry.status().name(),
                terms.stopLoss() != null);
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

    private static AppliedPlanResponse ofList(PlanOrderTerms terms, TradablePair pair, OrderListResponse list) {
        List<OrderResponse> placed = list.orders();
        OrderResponse entry = placed.getFirst();
        return new AppliedPlanResponse(
                terms.planId(),
                MarketType.SPOT,
                pair.symbol(),
                entry.orderId(),
                placed.stream().map(OrderResponse::orderId).toList(),
                entry.status().name(),
                true);
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
}
