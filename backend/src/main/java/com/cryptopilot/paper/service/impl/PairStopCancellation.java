package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.paper.entity.PaperAccount;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.entity.PaperOrderList;
import com.cryptopilot.paper.event.PaperOrderCanceled;
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
 * Cancels the paper orders of a pair the exchange stopped trading on Spot, one account at a time: an order that cannot
 * execute any more must not hold its funds (paper trading plan §6.2). Each order that works or waits in a group ends
 * CANCELED with {@code PAIR_NOT_TRADING}, what it locked is given back (once for the two orders of an OCO), the
 * Trader's order queue hears of it and the matching engine drops it.
 *
 * <p>Each account in its own transaction, under its paper lock, so a fill or a cancel of the Trader never interleaves
 * with it and a failure stays with its account. Run again, it finds nothing open and changes nothing.
 *
 * <p>Rule: TR-02, TR-10; NSF-01.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PairStopCancellation {

    /** Why the orders of a pair that stopped trading ended. */
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

    /** The accounts with an order of the pair that works or waits in a group. */
    @Transactional(readOnly = true)
    public List<UUID> accountsWithOrders(UUID pairId) {
        return orders.findAccountIdsWithOrders(pairId, CANCELLED);
    }

    /**
     * Cancels every order of the account on the pair that works or waits in a group.
     *
     * @return how many orders it cancelled; none when it ran before
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cancel(UUID accountId, UUID pairId) {
        // fk_paper_order_account keeps the account of an order stored.
        PaperAccount account = accounts.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("account " + accountId + " is not stored"));
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, account.getUserId());
        // Read only now, under the lock, so a fill or a cancel committed meanwhile is seen.
        List<PaperOrder> open =
                orders.findByAccountIdAndPairIdAndOrderStatusInOrderByCreatedAtAscIdAsc(accountId, pairId, CANCELLED);
        if (open.isEmpty()) {
            return 0;
        }
        PairCoins coins = market.pairCoins(pairId)
                .orElseThrow(() -> new IllegalStateException("pair " + pairId + " of a paper order is not stored"));
        Instant now = clock.instant();
        Set<UUID> cancelledGroups = new HashSet<>();
        for (PaperOrder order : open) {
            if (order.getListId() == null) {
                wallets.unlock(account, WalletType.SPOT, SpotArrival.lockedCoin(order, coins), order.lockedAmount());
                order.cancel(PAIR_NOT_TRADING, now);
                feed.order(account, order);
                events.publishEvent(new PaperOrderCanceled(order.getId()));
            } else if (cancelledGroups.add(order.getListId())) {
                lists.cancel(account, groupOf(order.getListId()), PAIR_NOT_TRADING, now);
            }
        }
        log.info(
                "TR-02 {} paper orders of account {} on pair {} cancelled: not trading",
                open.size(),
                accountId,
                pairId);
        return open.size();
    }

    // fk_paper_order_list keeps the group of an order stored.
    private PaperOrderList groupOf(UUID listId) {
        return groups.findById(listId)
                .orElseThrow(() -> new IllegalStateException("group " + listId + " of a paper order is not stored"));
    }
}
