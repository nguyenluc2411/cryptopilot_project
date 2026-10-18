package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.OrderBookDepth.PriceLevel;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.entity.PaperAccount;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.TriggerOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TriggerCondition;
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
 * <p>Rule: TR-02, TR-09, TR-11; NSF-07; Q-T6.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperMatchingServiceImpl implements PaperMatchingService {

    private static final Logger log = LoggerFactory.getLogger(PaperMatchingServiceImpl.class);

    private final PaperAccountRepository accounts;
    private final PaperOrderRepository orders;
    private final SpotSettlement settlement;
    private final SpotArrival arrival;
    private final PaperUserFeed feed;
    private final PaperOrderLists lists;
    private final PaperMatchingWatermarkRepository watermarks;
    private final UserLock lock;
    private final MarketApi market;
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
        // fk_paper_order_account keeps the account of an order stored.
        PaperAccount account = accounts.findById(resting.accountId())
                .orElseThrow(() -> new IllegalStateException("account " + resting.accountId() + " is not stored"));
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, account.getUserId());
        // Read only now, under the lock, so a cancel committed while this fill waited for it is seen.
        Optional<PaperOrder> found = orders.findById(resting.orderId());
        if (found.isEmpty() || !found.get().isOpen()) {
            return false;
        }
        PaperOrder order = found.get();
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
        lists.afterExecution(account, order, tradedAt);
        log.info("TR-02 paper order {} filled at {} ({}, {})", order.getId(), order.getLimitPrice(), tradedAt, source);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TriggerOrder> triggerOrders() {
        return orders.findAwaitingTrigger(OrderStatus.OPEN);
    }

    @Override
    @Transactional
    public boolean trigger(
            TriggerOrder watched, Instant at, BigDecimal stopPrice, FillSource source, BigDecimal candleOpen) {
        PaperAccount account = accounts.findById(watched.accountId())
                .orElseThrow(() -> new IllegalStateException("account " + watched.accountId() + " is not stored"));
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, account.getUserId());
        // Read only now, under the lock, so a cancel committed while this trigger waited for it is seen.
        Optional<PaperOrder> found = orders.findById(watched.orderId());
        if (found.isEmpty() || !found.get().awaitsTrigger()) {
            return false;
        }
        PaperOrder order = found.get();
        lists.beforeExecution(account, order, at);
        order.trigger(at);
        feed.order(account, order);
        log.info(
                "TR-09 paper order {} {} triggered at {} ({}, {})",
                order.getId(),
                order.getOrderType(),
                stopPrice,
                at,
                source);
        PairCoins coins = market.pairCoins(order.getPairId())
                .orElseThrow(() -> new IllegalStateException("pair " + order.getPairId() + " is not stored"));
        boolean replayed = source == FillSource.REPLAY;
        if (order.getOrderType().isSpotMarketStop()) {
            // Replayed, the book of then is not known: the stop, or the open of a candle that gapped past it.
            Optional<SpotArrival.Book> book = replayed
                    ? Optional.of(new SpotArrival.Book(
                            List.of(new PriceLevel(replayedStopPrice(order, stopPrice, candleOpen), BigDecimal.ZERO)),
                            false))
                    : arrival.bookFor(pairOf(order), order.getSide());
            arrival.marketOnTrigger(account, order, coins, book, replayed ? at : clock.instant(), source);
        } else if (replayed) {
            // The candles replayed after this one fill it at its price; the book of now is not the book of then.
            arrival.rest(account, order);
        } else {
            arrival.limitOnArrival(
                    account, order, coins, arrival.bookFor(pairOf(order), order.getSide()), clock.instant());
        }
        lists.afterExecution(account, order, at);
        return true;
    }

    /**
     * The price a replayed STOP_LOSS or TAKE_PROFIT executes at: the open of the candle that triggered it when its
     * trigger condition already held at that open, as the market had gapped through the stop; otherwise the stop
     * (Q-51). A trailing stop triggers on the pullback, the way of a STOP_LOSS of its side.
     *
     * <p>Rule: TR-09; PT-R14.
     *
     * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
     * University Press, ch. 4 (a triggered stop becomes a market order and executes at the next available price).
     */
    static BigDecimal replayedStopPrice(PaperOrder order, BigDecimal stopPrice, BigDecimal candleOpen) {
        if (candleOpen == null) {
            return stopPrice;
        }
        TriggerCondition condition = order.getCallbackRate() != null
                ? OrderType.STOP_LOSS.triggerConditionFor(order.getSide())
                : order.getTriggerCondition();
        return condition.isMet(candleOpen, stopPrice) ? candleOpen : stopPrice;
    }

    private TradablePair pairOf(PaperOrder order) {
        return market.tradablePair(order.getPairId(), order.getMarketType())
                .orElseThrow(() -> new IllegalStateException("pair " + order.getPairId() + " is not tradable"));
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
}
