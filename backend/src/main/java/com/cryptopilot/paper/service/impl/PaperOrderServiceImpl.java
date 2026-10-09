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
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.event.PaperOrderRested;
import com.cryptopilot.paper.model.OrderQuery;
import com.cryptopilot.paper.model.PlacedOrder;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TimeInForce;
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
 * {@link SpotSettlement}. A refusal of any kind rolls the whole placement back, so a refused order leaves no row.
 *
 * <h2>Prices and quantities</h2>
 *
 * <p>The price is rounded to the pair's tick (BR-30) and the quantity floored to its step (BR-23), then to the 8
 * decimals a balance holds; an order worth less than the pair's minimum notional is refused (MSG15), as the exchange
 * refuses it. A MARKET order, and a LIMIT order the market already stands at, executes at the current Spot last price
 * of the latest-price cache; none is ever executed at a price that is not current (NSF-03).
 *
 * <p>Rule: TR-02, TR-04; BR-21, BR-23, BR-30; NSF-03.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperOrderServiceImpl implements PaperOrderService {

    /** Why an IOC or FOK limit expired: the price was not there on arrival. */
    static final String NOT_FILLABLE = "NOT_FILLABLE_ON_ARRIVAL";

    /** Why a post-only (GTX) limit expired: it would have executed on arrival. */
    static final String WOULD_TAKE = "POST_ONLY_WOULD_TAKE";

    /** Decimals a Spot balance holds, so the decimals a quantity may have. */
    private static final int BALANCE_SCALE = Rounding.AMOUNT_SCALE;

    private static final Logger log = LoggerFactory.getLogger(PaperOrderServiceImpl.class);

    private final PaperAccountRepository accounts;
    private final PaperOrderRepository orders;
    private final PaperFillRepository fills;
    private final PaperWallets wallets;
    private final SpotSettlement settlement;
    private final UserLock lock;
    private final MarketApi market;
    private final ApplicationEventPublisher events;
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
        Optional<BigDecimal> last = market.currentLastPrice(MarketType.SPOT, pair.symbol());
        String key = request.clientOrderId() == null ? UuidV7.next().toString() : request.clientOrderId();
        PaperOrder order = request.type() == OrderType.MARKET
                ? placeMarket(account, pair, coins, terms, key, last)
                : placeLimit(account, pair, coins, terms, key, last);
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
        PairCoins coins = coinsOf(order.getPairId());
        wallets.unlock(account, WalletType.SPOT, lockedCoin(order, coins), order.lockedAmount());
        order.cancel(clock.instant());
        events.publishEvent(new PaperOrderCanceled(order.getId()));
        return toResponse(order, symbolsOf(List.of(order.getPairId())));
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
     * A MARKET order executes whole at the current last price, as a taker, paying from what is free; without a current
     * price it is refused and nothing is stored.
     */
    private PaperOrder placeMarket(
            PaperAccount account,
            TradablePair pair,
            PairCoins coins,
            Terms terms,
            String key,
            Optional<BigDecimal> last) {
        BigDecimal price = last.orElseThrow(() -> priceUnavailable(pair));
        BigDecimal quantity;
        PaperOrder order;
        if (terms.quoteAmount() != null) {
            quantity = scaled(pair.filters().floorQuantity(Rounding.divide(terms.quoteAmount(), price)));
            requireTradable(pair.filters(), price, quantity, "quoteOrderQty");
            order = PaperOrder.spotMarketBuyFor(account.getId(), pair.pairId(), key, terms.quoteAmount());
        } else {
            quantity = terms.quantity();
            requireTradable(pair.filters(), price, quantity, "quantity");
            order = PaperOrder.spotMarket(account.getId(), pair.pairId(), key, terms.side(), quantity);
        }
        orders.save(order);
        settlement.execute(
                account, order, coins, price, quantity, Liquidity.TAKER, FillSource.LIVE, clock.instant(), false);
        return order;
    }

    /**
     * A LIMIT order the last price already reached executes at that price, as a taker (a post-only one expires
     * instead); one it has not reached waits with what it needs locked (GTC, GTX) or expires (IOC, FOK). Without a
     * current price, a waiting order waits; an IOC or FOK order, which must be decided now, is refused.
     */
    private PaperOrder placeLimit(
            PaperAccount account,
            TradablePair pair,
            PairCoins coins,
            Terms terms,
            String key,
            Optional<BigDecimal> last) {
        requireTradable(pair.filters(), terms.price(), terms.quantity(), "quantity");
        TimeInForce timeInForce = terms.timeInForce();
        boolean decidedNow = timeInForce == TimeInForce.IOC || timeInForce == TimeInForce.FOK;
        if (last.isEmpty() && decidedNow) {
            throw priceUnavailable(pair);
        }
        PaperOrder order = orders.save(PaperOrder.spotLimit(
                account.getId(), pair.pairId(), key, terms.side(), timeInForce, terms.price(), terms.quantity()));
        boolean reached = last.isPresent() && reaches(terms.side(), last.get(), terms.price());
        if (reached && timeInForce != TimeInForce.GTX) {
            settlement.execute(
                    account,
                    order,
                    coins,
                    last.get(),
                    terms.quantity(),
                    Liquidity.TAKER,
                    FillSource.LIVE,
                    clock.instant(),
                    false);
            return order;
        }
        // An order that ends now still needs what it would have locked, as the exchange checks the balance first.
        wallets.preload(account, WalletType.SPOT, lockedCoin(order, coins));
        wallets.lock(account, WalletType.SPOT, lockedCoin(order, coins), order.lockedAmount());
        if (reached || decidedNow) {
            wallets.unlock(account, WalletType.SPOT, lockedCoin(order, coins), order.lockedAmount());
            order.expire(reached ? WOULD_TAKE : NOT_FILLABLE, clock.instant());
            return order;
        }
        events.publishEvent(new PaperOrderRested(new RestingOrder(
                order.getId(),
                account.getId(),
                order.getMarketType(),
                order.getPairId(),
                order.getSide(),
                order.getLimitPrice(),
                order.getCreatedAt())));
        return order;
    }

    /** Whether a price reaches a limit: at or below it for a buy, at or above it for a sell. */
    static boolean reaches(OrderSide side, BigDecimal price, BigDecimal limit) {
        int compared = price.compareTo(limit);
        return side == OrderSide.BUY ? compared <= 0 : compared >= 0;
    }

    /** Which fields this stage accepts for the order type, before the pair is looked up. */
    private static void requireShape(PlaceOrderRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.market() != MarketType.SPOT) {
            // Futures orders are the next stage of TR-02.
            errors.put("market", "MSG15");
        }
        switch (request.type()) {
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
        TimeInForce timeInForce = request.type() == OrderType.LIMIT && request.timeInForce() == null
                ? TimeInForce.GTC
                : request.timeInForce();
        return new Terms(request.side(), request.type(), timeInForce, price, quantity, request.quoteOrderQty());
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
                && sameValue(placed.getQuoteOrderAmount(), terms.quoteAmount());
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

    /** An order below the pair's minimum notional is refused on the field that sized it (MSG15). */
    private static void requireTradable(PairFilters filters, BigDecimal price, BigDecimal quantity, String field) {
        if (quantity.signum() == 0 || filters.isBelowMinNotional(price.multiply(quantity))) {
            throw new FieldValidationException(
                    "order of " + quantity.toPlainString() + " at " + price.toPlainString() + " is below the minimum"
                            + " notional of " + filters.minNotional().toPlainString(),
                    Map.of(field, "MSG15"));
        }
    }

    private static BusinessException priceUnavailable(TradablePair pair) {
        return new BusinessException(
                ErrorCode.MARKET_PRICE_UNAVAILABLE, "no current last price for " + pair.symbol() + " on SPOT");
    }

    /** What a working order locks: the quote coin for a buy, the base coin for a sell. */
    private static CoinListing lockedCoin(PaperOrder order, PairCoins coins) {
        return order.getSide() == OrderSide.BUY ? coins.quote() : coins.base();
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
        return new OrderResponse(
                order.getId(),
                order.getClientOrderId(),
                order.getPairId(),
                symbol,
                order.getMarketType(),
                order.getSide(),
                order.getOrderType(),
                order.getTimeInForce(),
                order.getLimitPrice(),
                order.getOrigQuantity(),
                amountOrNull(order.getQuoteOrderAmount()),
                order.getExecutedQuantity(),
                Rounding.toAmount(order.getCumQuoteAmount()),
                order.getAvgPrice(),
                order.getOrderStatus(),
                order.getStatusReason(),
                order.getCreatedAt(),
                order.getClosedAt());
    }

    private static FillResponse toResponse(PaperFill fill, Map<UUID, String> symbols, Map<UUID, String> assets) {
        String asset = Objects.requireNonNull(
                assets.get(fill.getFeeCoinId()),
                () -> "fee coin " + fill.getFeeCoinId() + " of fill " + fill.getId() + " is not stored");
        return new FillResponse(
                fill.getId(),
                fill.getOrderId(),
                fill.getPairId(),
                symbolOf(symbols, fill.getPairId()),
                fill.getMarketType(),
                fill.getSide(),
                fill.getFillPrice(),
                fill.getFillQuantity(),
                Rounding.toAmount(fill.getQuoteAmount()),
                Rounding.toAmount(fill.getFeeAmount()),
                asset,
                fill.getLiquidity(),
                fill.getFillSource(),
                fill.getTradedAt());
    }

    private static BigDecimal amountOrNull(BigDecimal amount) {
        return amount == null ? null : Rounding.toAmount(amount);
    }

    /**
     * The order's figures after rounding.
     *
     * @param price the LIMIT price on the tick; {@code null} for MARKET
     * @param quantity the quantity on the step; {@code null} for a MARKET buy by total
     * @param quoteAmount what a MARKET buy by total spends; otherwise {@code null}
     */
    private record Terms(
            OrderSide side,
            OrderType type,
            TimeInForce timeInForce,
            BigDecimal price,
            BigDecimal quantity,
            BigDecimal quoteAmount) {}
}
