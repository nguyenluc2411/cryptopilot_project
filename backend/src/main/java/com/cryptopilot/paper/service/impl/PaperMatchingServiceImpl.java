package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.entity.FuturesAccountConfig;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.model.CrossAccount;
import com.cryptopilot.paper.model.LiquidationLevel;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.ReplayedCandle;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.TriggerOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.repository.FuturesAccountConfigRepository;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperMatchingWatermarkRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import com.cryptopilot.paper.service.PaperMatchingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles a fill the matching engine decided against a cancel of the same order: both run under the Trader's paper
 * lock, and the fill reads the order's status only once it holds the lock, so exactly one of them finds the order
 * working. A fill replayed after a restart, or decided twice by two instances, finds it FILLED and does nothing. A
 * fill executes what the order has left, so one that took part of the book when it was placed (TR-06) fills the rest.
 *
 * A stop limit or a trailing stop the price triggered is triggered the same way, under the lock, and only once.
 *
 * <p>A Futures order's fill is applied to the Trader's position by {@link FuturesSettlement} (TR-16), and a Futures
 * conditional order the price triggered places the order it carries through {@link FuturesTrigger} (TR-20). An
 * isolated position a mark reached is liquidated through {@link FuturesLiquidation} (TR-22), cross positions through
 * {@link FuturesCrossLiquidation} (TR-23).
 *
 * <p>An order of a Spot pair the system no longer trades, stopped by the exchange or disabled by an administrator, is
 * cancelled instead ({@code PAIR_NOT_TRADING}), by the cancellation the pair's stop runs.
 *
 * <p>Rule: TR-02, TR-09, TR-11, TR-16, TR-20, TR-22, TR-23, TR-42; NSF-01, NSF-07; Q-T6.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperMatchingServiceImpl implements PaperMatchingService {

    private static final Logger log = LoggerFactory.getLogger(PaperMatchingServiceImpl.class);

    private final PaperAccountRepository accounts;
    private final PaperOrderRepository orders;
    private final SpotSettlement settlement;
    private final FuturesSettlement futuresSettlement;
    private final FuturesTrigger futuresTrigger;
    private final FuturesLiquidation liquidation;
    private final FuturesCrossLiquidation crossLiquidation;
    private final FuturesMarginCalls marginCalls;
    private final FuturesAccountConfigRepository accountConfigs;
    private final FuturesOrderLists futuresTpSl;
    private final SpotStopTrigger stopTrigger;
    private final PaperOrderLists lists;
    private final PairStopCancellation pairStops;
    private final PaperMatchingWatermarkRepository watermarks;
    private final UserLock lock;
    private final MarketApi market;
    private final FuturesMarkCatchUp markCatchUp;
    private final QuoteUsdtRate usdtRates;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<RestingOrder> restingOrders() {
        return orders.findResting(OrderStatus.OPEN);
    }

    @Override
    @Transactional
    public boolean fill(RestingOrder resting, Instant tradedAt, FillSource source) {
        return fill(resting, tradedAt, source, null);
    }

    @Override
    @Transactional
    public boolean fill(RestingOrder resting, Instant tradedAt, FillSource source, ReplayedCandle candle) {
        // fk_trade_order_user keeps the owner of an order stored.
        PaperAccount account = accounts.findByUserId(resting.userId())
                .orElseThrow(() -> new IllegalStateException("account " + resting.userId() + " is not stored"));
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, account.userId());
        // Read only now, under the lock, so a cancel committed while this fill waited for it is seen.
        Optional<PaperOrder> found = orders.findById(resting.orderId());
        if (found.isEmpty() || !found.get().isOpen()) {
            return false;
        }
        PaperOrder order = found.get();
        if (order.getMarketType() == MarketType.FUTURES) {
            if (order.getAmendedAt() != null && resting.placedAt().isBefore(order.getAmendedAt())) {
                // Amended since the engine tracked it (TR-25): the copy the engine reached is gone, the amended order
                // is tracked anew.
                return false;
            }
            // The rest at the limit price, as a maker; what the position does not take ends the order (TR-16). A
            // replayed fill is dated when it is applied, with the candle's time kept apart (plan 9.5).
            Instant at = source == FillSource.REPLAY ? replayedAt(order) : tradedAt;
            if (source == FillSource.REPLAY) {
                futuresSettlement.executeReplayed(
                        account,
                        order,
                        order.getLimitPrice(),
                        order.remainingQuantity(),
                        Liquidity.MAKER,
                        at,
                        tradedAt);
            } else {
                futuresSettlement.execute(
                        account, order, order.getLimitPrice(), order.remainingQuantity(), Liquidity.MAKER, source, at);
            }
            futuresTpSl.settle(account, order, at);
            log.info(
                    "TR-16 paper Futures order {} filled at {} ({}, {}): {}",
                    order.getId(),
                    order.getLimitPrice(),
                    tradedAt,
                    source,
                    order.getOrderStatus());
            return true;
        }
        if (cancelledAsNotTrading(account, order)) {
            return false;
        }
        PairCoins coins = market.pairCoins(order.getPairId())
                .orElseThrow(() -> new IllegalStateException("pair " + order.getPairId() + " is not stored"));
        lists.beforeExecution(account, order, tradedAt);
        settlement.execute(
                account,
                order,
                coins,
                order.getLimitPrice(),
                order.remainingQuantity(),
                Liquidity.MAKER,
                source,
                tradedAt,
                true);
        lists.afterExecution(account, order, tradedAt, source, candle);
        log.info("TR-02 paper order {} filled at {} ({}, {})", order.getId(), order.getLimitPrice(), tradedAt, source);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TriggerOrder> triggerOrders() {
        return orders.findAwaitingTrigger(OrderStatus.OPEN);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TriggerOrder> markTriggerOrders() {
        return orders.findAwaitingMarkTrigger(OrderStatus.OPEN);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LiquidationLevel> liquidationLevels() {
        return liquidation.levels();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LiquidationLevel> liquidationLevels(UUID userId, UUID pairId) {
        return liquidation.levels(userId, pairId);
    }

    @Override
    @Transactional
    public boolean liquidate(LiquidationLevel level, BigDecimal mark, Instant at) {
        PaperAccount account = accounts.findByUserId(level.userId())
                .orElseThrow(() -> new IllegalStateException("account " + level.userId() + " is not stored"));
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, account.userId());
        return liquidation.liquidate(account, level.positionId(), mark, at);
    }

    @Override
    @Transactional
    public Optional<UUID> liquidateCross(UUID userId, UUID pairId, Instant at) {
        Optional<PaperAccount> account = accounts.findByUserId(userId);
        if (account.isEmpty()) {
            return Optional.empty();
        }
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        return crossLiquidation.step(account.get(), pairId, at);
    }

    @Override
    @Transactional
    public boolean marginCall(UUID userId, UUID positionId, BigDecimal mark, Instant at) {
        return accounts.findByUserId(userId)
                .map(account -> marginCalls.call(account, positionId, mark, at))
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> liquidatingSince(Instant at) {
        return accountConfigs.findByLiquidatingSinceLessThanEqual(at).stream()
                .map(FuturesAccountConfig::userId)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CrossAccount> crossAccounts() {
        return crossLiquidation.accounts();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CrossAccount> crossAccount(UUID userId) {
        return crossLiquidation.account(userId);
    }

    @Override
    @Transactional
    public boolean trigger(
            TriggerOrder watched, Instant at, BigDecimal stopPrice, FillSource source, BigDecimal candleOpen) {
        PaperAccount account = accounts.findByUserId(watched.userId())
                .orElseThrow(() -> new IllegalStateException("account " + watched.userId() + " is not stored"));
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, account.userId());
        // Read only now, under the lock, so a cancel committed while this trigger waited for it is seen.
        Optional<PaperOrder> found = orders.findById(watched.orderId());
        if (found.isEmpty() || !found.get().awaitsTrigger()) {
            return false;
        }
        PaperOrder order = found.get();
        if (order.getMarketType() == MarketType.FUTURES) {
            // A replay reached the trigger in the past; the order it places takes the book now (plan 9.5).
            futuresTrigger.execute(account, order, source == FillSource.REPLAY ? clock.instant() : at, source);
            return true;
        }
        if (cancelledAsNotTrading(account, order)) {
            return false;
        }
        PairCoins coins = market.pairCoins(order.getPairId())
                .orElseThrow(() -> new IllegalStateException("pair " + order.getPairId() + " is not stored"));
        lists.beforeExecution(account, order, at);
        stopTrigger.execute(account, order, coins, at, stopPrice, source, candleOpen);
        // A stop is never the working order of a group, so no candle is needed to place anything after it.
        lists.afterExecution(account, order, at, source, null);
        return true;
    }

    /**
     * When a replayed Futures fill is dated: when it is applied (plan 9.5), but never past the order's good-till date,
     * which the candle it was decided on came before: a GTD order reached in time is not on record as filled after it
     * expired.
     */
    private Instant replayedAt(PaperOrder order) {
        Instant now = clock.instant();
        Instant until = order.getGoodTillDate();
        return until != null && !until.isAfter(now) ? until.minusMillis(1) : now;
    }

    /**
     * An order of a Spot pair the system no longer trades is cancelled instead of executed, as the pair's stop does
     * (NSF-01, BR-07): a fill or trigger decided while the stop or the disabling was being committed would otherwise
     * execute it, or find no tradable pair to execute on.
     *
     * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 (write skew:
     * the status is checked again under the lock the cancellation takes).
     */
    private boolean cancelledAsNotTrading(PaperAccount account, PaperOrder order) {
        if (order.getMarketType() != MarketType.SPOT) {
            return false;
        }
        if (!pairStops.outOfService(order.getPairId())) {
            return false;
        }
        pairStops.cancel(account, order);
        log.info("NSF-01 paper order {} cancelled instead of executed: its pair is not traded", order.getId());
        return true;
    }

    @Override
    @Transactional
    public void saveExtremes(Map<UUID, BigDecimal> extremes) {
        if (extremes.isEmpty()) {
            return;
        }
        for (PaperOrder order : orders.findByIdIn(extremes.keySet())) {
            if (order.awaitsTrigger()) {
                order.moveExtreme(extremes.get(order.getId()));
            }
        }
    }

    /** No transaction: no connection is held while the exchange answers. */
    @Override
    public void prefetchQuoteRates(MarketType market, UUID pairId, Instant from, Instant to) {
        if (market != MarketType.SPOT) {
            return;
        }
        try {
            this.market.pairCoins(pairId).ifPresent(coins -> usdtRates.prefetch(coins, from, to));
        } catch (RuntimeException failure) {
            log.warn("Q-52 quote rates of pair {} not prefetched: {}", pairId, failure.toString());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> watermark(MarketType market, UUID pairId) {
        return watermarks.lastCandleOpenTime(market.name(), pairId);
    }

    @Override
    @Transactional
    public void advanceWatermark(MarketType market, UUID pairId, Instant openTime) {
        watermarks.advance(market.name(), pairId, openTime, clock.instant());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> markWatermark(UUID pairId) {
        return watermarks.lastMarkCandleOpenTime(pairId);
    }

    @Override
    @Transactional
    public void advanceMarkWatermark(UUID pairId, Instant openTime) {
        watermarks.advanceMark(pairId, openTime, clock.instant());
    }

    @Override
    public void markCatchingUp(UUID pairId, boolean catchingUp) {
        market.pairListings(Set.of(pairId)).forEach(pair -> markCatchUp.set(pair.symbol(), catchingUp));
    }
}
