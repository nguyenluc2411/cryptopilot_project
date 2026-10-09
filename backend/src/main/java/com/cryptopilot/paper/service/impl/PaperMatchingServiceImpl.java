package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.entity.PaperAccount;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperMatchingWatermarkRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import com.cryptopilot.paper.service.PaperMatchingService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
 * working. A fill replayed after a restart, or decided twice by two instances, finds it FILLED and does nothing.
 *
 * <p>Rule: TR-02; NSF-07; Q-T6.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperMatchingServiceImpl implements PaperMatchingService {

    private static final Logger log = LoggerFactory.getLogger(PaperMatchingServiceImpl.class);

    private final PaperAccountRepository accounts;
    private final PaperOrderRepository orders;
    private final SpotSettlement settlement;
    private final PaperMatchingWatermarkRepository watermarks;
    private final UserLock lock;
    private final MarketApi market;
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
        settlement.execute(
                account,
                order,
                coins,
                order.getLimitPrice(),
                order.getOrigQuantity(),
                Liquidity.MAKER,
                source,
                tradedAt,
                true);
        log.info("TR-02 paper order {} filled at {} ({}, {})", order.getId(), order.getLimitPrice(), tradedAt, source);
        return true;
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
