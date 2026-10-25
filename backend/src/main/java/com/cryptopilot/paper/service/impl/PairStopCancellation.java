package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.entity.PaperOrderList;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperOrderListRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels the paper orders of a pair the system no longer trades on Spot, one account at a time: the exchange stopped
 * trading it, or an administrator took its Spot market out of service (BR-07), so no stream feeds it; an order that
 * cannot execute any more must not hold its funds (paper trading plan §6.2). Each order that works or waits in a group ends
 * CANCELED with {@code PAIR_NOT_TRADING}, what it locked is given back (once for the two orders of an OCO), the
 * Trader's order queue hears of it and the matching engine drops it.
 *
 * <p>Each account in its own transaction, under its paper lock, so a fill or a cancel of the Trader never interleaves
 * with it and a failure stays with its account. Run again, it finds nothing open and changes nothing. Only Spot orders
 * are touched. A fill or a trigger that reaches an order of such a pair cancels it the same way.
 *
 * <p>Rule: TR-02, TR-10; NSF-01; BR-07, UC-45.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PairStopCancellation {

    /** Why the orders of a pair the system no longer trades ended, whichever of the two took it out. */
    public static final String PAIR_NOT_TRADING = "PAIR_NOT_TRADING";

    /** The statuses of an order this cancels: working, or waiting in a group to be placed. */
    private static final Set<OrderStatus> CANCELLED =
            EnumSet.of(OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_NEW);

    private static final Logger log = LoggerFactory.getLogger(PairStopCancellation.class);

    private final PaperAccountRepository accounts;
    private final PaperOrderRepository orders;
    private final PaperOrderListRepository groups;
    private final PaperWallets wallets;
    private final PaperOrderLists lists;
    private final PaperUserFeed feed;
    private final UserLock lock;
    private final MarketApi market;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** The pairs with a Spot order that works or waits in a group. */
    @Transactional(readOnly = true)
    public List<UUID> pairsWithOrders() {
        return orders.findPairIdsWithOrders(MarketType.SPOT, CANCELLED);
    }

    /**
     * Whether the system no longer trades the pair on Spot: the exchange stopped trading it (NSF-01), or the pair or its
     * Spot market is disabled (BR-07), which {@link MarketApi#tradablePair} reports as no tradable pair at all.
     */
    public boolean outOfService(UUID pairId) {
        return market.tradablePair(pairId, MarketType.SPOT)
                .map(pair -> !pair.exchangeTrading())
                .orElse(true);
    }

    /** The accounts with a Spot order of the pair that works or waits in a group. */
    @Transactional(readOnly = true)
    public List<UUID> accountsWithOrders(UUID pairId) {
        return orders.findUserIdsWithOrders(pairId, MarketType.SPOT, CANCELLED);
    }

    /**
     * Cancels every order of the account on the pair that works or waits in a group.
     *
     * @return how many orders it cancelled; none when it ran before
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cancel(UUID userId, UUID pairId) {
        // fk_trade_order_user keeps the owner of an order stored.
        PaperAccount account = accounts.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("account " + userId + " is not stored"));
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, account.userId());
        // Read only now, under the lock, so a fill or a cancel committed meanwhile is seen.
        List<PaperOrder> open = orders.findByUserIdAndPairIdAndMarketTypeAndOrderStatusInOrderByCreatedAtAscIdAsc(
                userId, pairId, MarketType.SPOT, CANCELLED);
        if (open.isEmpty()) {
            return 0;
        }
        PairCoins coins = coinsOf(pairId);
        Instant now = clock.instant();
        Set<UUID> cancelledGroups = new HashSet<>();
        for (PaperOrder order : open) {
            if (order.getListId() == null || cancelledGroups.add(order.getListId())) {
                cancel(account, order, coins, now);
            }
        }
        log.info("TR-02 {} paper orders of account {} on pair {} cancelled: not trading", open.size(), userId, pairId);
        return open.size();
    }

    /**
     * Cancels one order of a pair that stopped trading, or the whole group it belongs to, inside the caller's
     * transaction, which holds the Trader's paper lock: for a fill or a trigger that reached it first (NSF-01).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(PaperAccount account, PaperOrder order) {
        cancel(account, order, coinsOf(order.getPairId()), clock.instant());
    }

    private void cancel(PaperAccount account, PaperOrder order, PairCoins coins, Instant now) {
        if (order.getListId() != null) {
            lists.cancel(account, groupOf(order.getListId()), PAIR_NOT_TRADING, now);
            return;
        }
        wallets.unlock(account, WalletType.SPOT, SpotArrival.lockedCoin(order, coins), order.lockedAmount());
        order.cancel(PAIR_NOT_TRADING, now);
        feed.order(account, order);
        events.publishEvent(new PaperOrderCanceled(order.getId()));
    }

    private PairCoins coinsOf(UUID pairId) {
        return market.pairCoins(pairId)
                .orElseThrow(() -> new IllegalStateException("pair " + pairId + " of a paper order is not stored"));
    }

    // fk_trade_order_list keeps the group of an order stored.
    private PaperOrderList groupOf(UUID listId) {
        return groups.findById(listId)
                .orElseThrow(() -> new IllegalStateException("group " + listId + " of a paper order is not stored"));
    }
}
