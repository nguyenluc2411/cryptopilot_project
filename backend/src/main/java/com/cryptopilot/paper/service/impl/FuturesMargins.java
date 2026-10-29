package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.calculator.FuturesMarginMath;
import com.cryptopilot.paper.config.PaperAccountProperties;
import com.cryptopilot.paper.config.PaperFuturesProperties;
import com.cryptopilot.paper.entity.FuturesAccountConfig;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.entity.FuturesSymbolConfig;
import com.cryptopilot.paper.entity.PaperBalance;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.exception.FuturesRejection;
import com.cryptopilot.paper.model.FuturesCrossState;
import com.cryptopilot.paper.model.PendingOrderMargin;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.PositionStatus;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.FuturesAccountConfigRepository;
import com.cryptopilot.paper.repository.FuturesPositionRepository;
import com.cryptopilot.paper.repository.FuturesSymbolConfigRepository;
import com.cryptopilot.paper.repository.PaperBalanceRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads what the Futures rules need from the database and the price caches: the Trader's Futures settings (created on
 * first use), a pair's settings and brackets, the mark prices of the pairs with positions, the margin of the open
 * orders and the cross side of the account. Callers hold the Trader's lock, so what they read here is what they write
 * against.
 *
 * <p>A pair whose mark price is older than {@code markMaxAge} is {@code MARK_STALE} (plan 8.2.1): values still use its
 * last known mark, but nothing that opens risk is decided on it, and an isolated position funded meanwhile counts the
 * stale cross positions at a loss ({@link #crossState(UUID, UUID, boolean)}).
 *
 * <p>Rule: TR-14, TR-15, TR-18, TR-22a; D-127.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class FuturesMargins {

    private final FuturesAccountConfigRepository accountConfigs;
    private final FuturesSymbolConfigRepository symbolConfigs;
    private final FuturesPositionRepository positions;
    private final PaperOrderRepository orders;
    private final PaperBalanceRepository balances;
    private final MarketApi market;
    private final PaperFuturesProperties futures;
    private final EntityManager entityManager;

    /** The Trader's Futures settings, created with the defaults before the first Futures action. */
    FuturesAccountConfig accountConfig(UUID userId) {
        return accountConfigs
                .findById(userId)
                .orElseGet(() -> accountConfigs.save(FuturesAccountConfig.defaults(userId)));
    }

    /**
     * Raises the Futures account version at commit, after a change of the account (its wallet, a position or an
     * isolated wallet), so a snapshot cached before it is known to be stale. Forced, because the settings row itself
     * may not have changed.
     */
    void recordChange(FuturesAccountConfig config) {
        entityManager.lock(config, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    }

    /**
     * A Futures pair the exchange trades and that has leverage brackets; without them it cannot be margined.
     *
     * @throws ResourceNotFoundException MSG41 for a symbol not listed or not traded on Futures
     * @throws FuturesRejection {@code PAIR_NOT_TRADING} ({@code -4141}) for a pair without brackets
     */
    FuturesPair pair(String symbol) {
        String name = symbol.strip().toUpperCase(Locale.ROOT);
        PairListing listing = market.pairListing(name)
                .filter(PairListing::futuresListed)
                .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", name));
        TradablePair tradable = market.tradablePair(listing.pairId(), MarketType.FUTURES)
                .filter(TradablePair::exchangeTrading)
                .orElseThrow(() -> new ResourceNotFoundException("CryptoPair", name));
        List<LeverageTier> tiers = market.leverageBrackets(tradable.pairId());
        if (tiers.isEmpty()) {
            throw new FuturesRejection(ErrorCode.PAIR_NOT_TRADING, -4141, "Symbol is closed.", name);
        }
        return new FuturesPair(tradable.pairId(), tradable.symbol(), tradable.filters(), tiers);
    }

    /** The Trader's settings of a pair, or the defaults (20x or the pair's highest, CROSSED) of one never changed. */
    FuturesSymbolConfig symbolConfig(UUID userId, UUID pairId, List<LeverageTier> tiers) {
        return symbolConfigs
                .findByUserIdAndPairId(userId, pairId)
                .orElseGet(() -> FuturesSymbolConfig.defaults(userId, pairId, FuturesMarginMath.maxLeverage(tiers)));
    }

    /** The Trader's open one-way position on the pair, or {@code null} when flat. */
    FuturesPosition openPosition(UUID userId, UUID pairId) {
        return positions.findByUserIdAndPairIdAndPositionStatus(userId, pairId, PositionStatus.OPEN).stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * The Trader's open Futures MARKET and LIMIT orders, newest first. A conditional order is not one of them: it holds
     * no margin until its trigger places the order it carries (plan 4.7), and it is listed by the algo endpoints.
     */
    List<PaperOrder> openOrders(UUID userId) {
        return orders
                .findByUserIdAndMarketTypeAndOrderStatusInOrderByCreatedAtDescIdDesc(
                        userId, MarketType.FUTURES, OrderStatus.OPEN)
                .stream()
                .filter(order -> !order.isConditional())
                .toList();
    }

    /** The Trader's open Futures conditional orders, newest first (TR-20). */
    List<PaperOrder> openConditionalOrders(UUID userId) {
        return orders
                .findByUserIdAndMarketTypeAndOrderStatusInOrderByCreatedAtDescIdDesc(
                        userId, MarketType.FUTURES, OrderStatus.OPEN)
                .stream()
                .filter(PaperOrder::isConditional)
                .toList();
    }

    /** The coin of the Futures wallet, USDT. */
    CoinListing usdt() {
        return market.coinsBySymbol(Set.of(PaperAccountProperties.FUTURES_COIN)).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Coin", PaperAccountProperties.FUTURES_COIN));
    }

    /**
     * The current mark price of each pair of the positions.
     *
     * @throws BusinessException {@code MARKET_PRICE_UNAVAILABLE} when a pair has no mark price, so no margin can be
     *     computed
     */
    Map<UUID, BigDecimal> marks(Collection<FuturesPosition> positions) {
        Set<UUID> pairIds = positions.stream().map(FuturesPosition::getPairId).collect(Collectors.toSet());
        Map<UUID, BigDecimal> marks = new HashMap<>();
        for (PairListing pair : market.pairListings(pairIds)) {
            BigDecimal mark = market.currentMarkPrice(pair.symbol())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.MARKET_PRICE_UNAVAILABLE, "no mark price of " + pair.symbol() + " is known"));
            marks.put(pair.pairId(), mark);
        }
        return marks;
    }

    /** The pair's mark price when it is current; empty while the pair is {@code MARK_STALE} (8.2.1). */
    Optional<BigDecimal> freshMark(String symbol) {
        return market.currentMarkPrice(symbol, futures.markMaxAge());
    }

    /**
     * Refuses what 8.2.1 rule 3 refuses while a pair of one of the Trader's cross positions is {@code MARK_STALE}: the
     * margin available and what can be withdrawn depend on every cross mark, so a transfer out, an order that opens risk
     * or margin added to an isolated position waits for the mark.
     *
     * @param except a pair whose own staleness the caller judges by rule 1, or {@code null}
     * @throws BusinessException {@code MARKET_PRICE_UNAVAILABLE} naming the stale pair
     */
    void requireFreshCrossMarks(UUID userId, UUID except) {
        List<FuturesPosition> cross = positions.findByUserIdAndPositionStatus(userId, PositionStatus.OPEN).stream()
                .filter(position -> position.getMarginType() == MarginType.CROSSED)
                .filter(position -> !position.getPairId().equals(except))
                .toList();
        List<PairListing> stale = stalePairs(cross);
        if (!stale.isEmpty()) {
            throw staleMark(stale.getFirst().symbol());
        }
    }

    /** {@code MARKET_PRICE_UNAVAILABLE} for a pair whose mark price is stale. */
    static BusinessException staleMark(String symbol) {
        return new BusinessException(
                ErrorCode.MARKET_PRICE_UNAVAILABLE, "the mark price of " + symbol + " is stale (MARK_STALE)");
    }

    /** The pairs of these positions whose mark price is stale. */
    private List<PairListing> stalePairs(Collection<FuturesPosition> positions) {
        if (positions.isEmpty()) {
            return List.of();
        }
        Set<UUID> pairIds = positions.stream().map(FuturesPosition::getPairId).collect(Collectors.toSet());
        return market.pairListings(pairIds).stream()
                .filter(pair -> freshMark(pair.symbol()).isEmpty())
                .toList();
    }

    /**
     * The margin of one pair's open orders in one-way mode at the current prices: the larger of the two sides' costs,
     * counting only what would open or increase the position; reduce-only orders cost nothing (4.7).
     *
     * @param positionQuantity the signed size of the pair's position, zero when flat
     * @param pending the pair's open orders, as what is left of each
     * @param leverage the pair's leverage
     * @param last the last price, or {@code null}; then a LIMIT order is costed at its own price
     * @param mark the mark price, or {@code null}; likewise
     */
    static BigDecimal pairOrderMargin(
            BigDecimal positionQuantity, List<Pending> pending, int leverage, BigDecimal last, BigDecimal mark) {
        List<PendingOrderMargin> costed = new ArrayList<>(pending.size());
        for (Pending order : pending) {
            if (order.reduceOnly()) {
                continue;
            }
            BigDecimal reference = order.limitPrice() != null ? order.limitPrice() : last;
            BigDecimal lastPrice = last != null ? last : reference;
            BigDecimal markPrice = mark != null ? mark : reference;
            costed.add(new PendingOrderMargin(
                    order.side(),
                    order.quantity(),
                    FuturesMarginMath.orderCost(
                            order.side(), order.quantity(), order.limitPrice(), lastPrice, markPrice, leverage)));
        }
        return FuturesMarginMath.openOrderInitialMargin(positionQuantity, costed);
    }

    /**
     * The cross side of the account at the current marks: the positions, and the margin of every open order but the
     * one excluded.
     *
     * @param excludedOrderId an order whose rest is left out, as if it no longer waited (4.3 rule 6), or {@code null}
     */
    FuturesCrossState crossState(UUID userId, UUID excludedOrderId) {
        return crossState(userId, excludedOrderId, false);
    }

    /**
     * As {@link #crossState(UUID, UUID)}; when {@code conservative}, a cross position on a {@code MARK_STALE} pair counts
     * {@code min(0, uPnL at its last mark) − notional × staleMarkHaircut}, so an isolated position funded while a mark
     * is lost never takes cash the stale positions may already have lost (8.2.1 rule 4).
     */
    FuturesCrossState crossState(UUID userId, UUID excludedOrderId, boolean conservative) {
        List<FuturesPosition> open = positions.findByUserIdAndPositionStatus(userId, PositionStatus.OPEN);
        Map<UUID, BigDecimal> marks = marks(open);
        FuturesCrossState state = crossState(userId, open, marks, openOrderMargin(userId, open, excludedOrderId));
        if (!conservative) {
            return state;
        }
        List<FuturesPosition> cross = open.stream()
                .filter(position -> position.getMarginType() == MarginType.CROSSED)
                .toList();
        Set<UUID> stale = stalePairs(cross).stream().map(PairListing::pairId).collect(Collectors.toSet());
        BigDecimal unrealized = state.crossUnrealizedPnl();
        for (FuturesPosition position : cross) {
            if (!stale.contains(position.getPairId())) {
                continue;
            }
            BigDecimal mark = marks.get(position.getPairId());
            BigDecimal atMark = FuturesMarginMath.unrealizedPnl(
                    position.getDirection(), position.getQuantity(), position.getEntryPrice(), mark);
            BigDecimal haircut = position.getQuantity().multiply(mark).multiply(futures.staleMarkHaircut());
            unrealized =
                    unrealized.subtract(atMark).add(atMark.min(BigDecimal.ZERO)).subtract(haircut);
        }
        return new FuturesCrossState(
                state.walletBalance(),
                state.crossWalletBalance(),
                Rounding.toAmount(unrealized),
                state.crossInitialMargin(),
                state.openOrderInitialMargin());
    }

    /**
     * The cross side of the account at the marks, from the open positions given.
     *
     * @param openOrderInitialMargin the margin of the open orders
     */
    FuturesCrossState crossState(
            UUID userId,
            List<FuturesPosition> openPositions,
            Map<UUID, BigDecimal> marks,
            BigDecimal openOrderInitialMargin) {
        BigDecimal wallet = futuresWallet(userId);
        BigDecimal isolated = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal initial = BigDecimal.ZERO;
        for (FuturesPosition position : openPositions) {
            if (position.getMarginType() == MarginType.ISOLATED) {
                isolated = isolated.add(position.getIsolatedWalletAmount());
                continue;
            }
            BigDecimal mark = marks.get(position.getPairId());
            unrealized = unrealized.add(FuturesMarginMath.unrealizedPnl(
                    position.getDirection(), position.getQuantity(), position.getEntryPrice(), mark));
            initial = initial.add(
                    FuturesMarginMath.initialMargin(position.getQuantity().multiply(mark), position.getLeverage()));
        }
        return new FuturesCrossState(wallet, wallet.subtract(isolated), unrealized, initial, openOrderInitialMargin);
    }

    /** The margin of every open Futures order of the Trader but the one excluded, pair by pair. */
    BigDecimal openOrderMargin(UUID userId, List<FuturesPosition> openPositions, UUID excludedOrderId) {
        Map<UUID, List<Pending>> byPair = new LinkedHashMap<>();
        for (PaperOrder order : openOrders(userId)) {
            if (!order.getId().equals(excludedOrderId)) {
                byPair.computeIfAbsent(order.getPairId(), ignored -> new ArrayList<>())
                        .add(Pending.of(order));
            }
        }
        if (byPair.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Map<UUID, BigDecimal> sizes = openPositions.stream()
                .collect(Collectors.toMap(FuturesPosition::getPairId, FuturesPosition::signedQuantity));
        BigDecimal total = BigDecimal.ZERO;
        for (PairListing pair : market.pairListings(byPair.keySet())) {
            int leverage = symbolConfig(userId, pair.pairId(), market.leverageBrackets(pair.pairId()))
                    .getLeverage();
            total = total.add(pairOrderMargin(
                    sizes.getOrDefault(pair.pairId(), BigDecimal.ZERO),
                    byPair.get(pair.pairId()),
                    leverage,
                    market.currentLastPrice(MarketType.FUTURES, pair.symbol()).orElse(null),
                    market.currentMarkPrice(pair.symbol()).orElse(null)));
        }
        return total;
    }

    /** The Futures wallet balance, the sum of its ledger; may be below zero (D-127). */
    BigDecimal futuresWallet(UUID userId) {
        return balances.findByUserIdAndWalletTypeAndCoinId(userId, WalletType.FUTURES, usdt().coinId())
                .map(PaperBalance::getFreeAmount)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * A Futures pair with its filters and brackets.
     *
     * @param filters the pair's Futures filters
     * @param tiers its leverage brackets, never empty
     */
    record FuturesPair(UUID pairId, String symbol, PairFilters filters, List<LeverageTier> tiers) {}

    /**
     * What is left of an open order, as its margin sees it; a new order before it is stored too.
     *
     * @param limitPrice the limit price, or {@code null} for a MARKET order
     * @param quantity the quantity still to fill, positive
     */
    record Pending(OrderSide side, BigDecimal limitPrice, BigDecimal quantity, boolean reduceOnly) {

        static Pending of(PaperOrder order) {
            return new Pending(order.getSide(), order.getLimitPrice(), order.remainingQuantity(), order.isReduceOnly());
        }
    }
}
