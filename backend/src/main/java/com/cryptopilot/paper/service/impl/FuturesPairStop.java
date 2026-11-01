package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.PositionCloseReason;
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.model.enums.PositionStatus;
import com.cryptopilot.paper.repository.FuturesPositionRepository;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes a Trader out of a pair the system no longer trades on Futures (TR-46, plan 9.7): an administrator disabled
 * its Futures market or the whole pair (UC-45), or the exchange stopped trading it. As the exchange settles a delisted
 * contract:
 *
 * <ul>
 *   <li>every order and conditional order of the pair that works or waits in a group ends CANCELED with
 *       {@code PAIR_NOT_TRADING}, so nothing opens or triggers there again;
 *   <li>then every open position of the pair is closed whole at the latest mark price by a system MARKET order
 *       ({@code system-<time>-<position>}), with {@code close_reason = SYSTEM}: realised as any close, its isolated wallet released.
 * </ul>
 *
 * <p>The cancels and the closes of an account commit in two transactions, each under its paper lock, as
 * {@link PairStopCancellation} does for Spot: a close that cannot be priced never undoes the cancels. Run again, it
 * finds nothing open and changes nothing. A pair with no mark price at all keeps its positions until one comes; the
 * sweep tries again.
 *
 * <p>Rule: TR-46; UC-45; D-115, D-127.
 */
@Component
public class FuturesPairStop {

    /** The client order id prefix of the orders that close positions of a pair taken out of service. */
    static final String SYSTEM_CLOSE = "system-";

    /** The statuses of an order this cancels: working, or waiting in a group to be placed. */
    private static final Set<OrderStatus> CANCELLED =
            EnumSet.of(OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_NEW);

    private static final Logger log = LoggerFactory.getLogger(FuturesPairStop.class);

    private final PaperAccountRepository accounts;
    private final PaperOrderRepository orders;
    private final FuturesPositionRepository positions;
    private final FuturesSettlement settlement;
    private final FuturesOrderLists tpSl;
    private final PaperUserFeed feed;
    private final UserLock lock;
    private final MarketApi market;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate transactions;

    FuturesPairStop(
            PaperAccountRepository accounts,
            PaperOrderRepository orders,
            FuturesPositionRepository positions,
            FuturesSettlement settlement,
            FuturesOrderLists tpSl,
            PaperUserFeed feed,
            UserLock lock,
            MarketApi market,
            ApplicationEventPublisher events,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.orders = orders;
        this.positions = positions;
        this.settlement = settlement;
        this.tpSl = tpSl;
        this.feed = feed;
        this.lock = lock;
        this.market = market;
        this.events = events;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The pairs with a Futures order that works or waits in a group, or an open position. */
    @Transactional(readOnly = true)
    public List<UUID> pairsInUse() {
        Set<UUID> pairs = new LinkedHashSet<>(orders.findPairIdsWithOrders(MarketType.FUTURES, CANCELLED));
        pairs.addAll(positions.findHeldPairIds());
        return List.copyOf(pairs);
    }

    /**
     * Whether the system no longer trades the pair on Futures: the exchange stopped trading it, or the pair or its
     * Futures market is disabled (BR-07), which {@link MarketApi#tradablePair} reports as no tradable pair at all.
     */
    public boolean outOfService(UUID pairId) {
        return market.tradablePair(pairId, MarketType.FUTURES)
                .map(pair -> !pair.exchangeTrading())
                .orElse(true);
    }

    /** The accounts with a Futures order of the pair that works or waits in a group, or an open position of it. */
    @Transactional(readOnly = true)
    public List<UUID> accountsOn(UUID pairId) {
        Set<UUID> users = new LinkedHashSet<>(orders.findUserIdsWithOrders(pairId, MarketType.FUTURES, CANCELLED));
        users.addAll(positions.findHolderIds(pairId));
        return List.copyOf(users);
    }

    /**
     * Cancels the account's orders of the pair, then closes its positions there at the latest mark. The cancels commit
     * first, so they stay even when no mark is known to close at.
     *
     * @return how many orders it cancelled and positions it closed; none when it ran before
     */
    public int stop(UUID userId, UUID pairId) {
        int cancelled = transactions.execute(status -> cancel(userId, pairId));
        int closed = transactions.execute(status -> close(userId, pairId));
        int done = cancelled + closed;
        if (done > 0) {
            log.info(
                    "TR-46 {} Futures orders and positions of account {} on pair {} ended: not trading",
                    done,
                    userId,
                    pairId);
        }
        return done;
    }

    private int cancel(UUID userId, UUID pairId) {
        PaperAccount account = account(userId);
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        Instant now = clock.instant();
        int cancelled = 0;
        // Read only now, under the lock, so a fill or a cancel committed meanwhile is seen.
        for (PaperOrder order : orders.findByUserIdAndPairIdAndMarketTypeAndOrderStatusInOrderByCreatedAtAscIdAsc(
                userId, pairId, MarketType.FUTURES, CANCELLED)) {
            // A take profit or stop loss may have been cancelled with the order of its group just before.
            if (order.isOpen() || order.isPending()) {
                order.cancel(PairStopCancellation.PAIR_NOT_TRADING, now);
                feed.futuresOrder(account, order);
                events.publishEvent(new PaperOrderCanceled(order.getId()));
                tpSl.settle(account, order, now);
                cancelled++;
            }
        }
        return cancelled;
    }

    private int close(UUID userId, UUID pairId) {
        PaperAccount account = account(userId);
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, userId);
        List<FuturesPosition> open =
                positions.findByUserIdAndPairIdAndPositionStatus(userId, pairId, PositionStatus.OPEN);
        if (open.isEmpty()) {
            return 0;
        }
        PairListing pair = market.pairListings(Set.of(pairId)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("pair " + pairId + " is not stored"));
        Optional<BigDecimal> mark = closingMark(pair);
        if (mark.isEmpty()) {
            log.warn(
                    "TR-46 no mark price of {} to close the {} Futures positions of account {} at; tried again later",
                    pair.symbol(),
                    open.size(),
                    userId);
            return 0;
        }
        Instant now = clock.instant();
        for (FuturesPosition position : open) {
            close(account, position, mark.get(), now);
        }
        return open.size();
    }

    /**
     * The cached mark, else the latest stored one: a delisted contract settles at its last known mark price, and a
     * stale value read once beats a close that never happens.
     *
     * <p>References: Binance, USDⓈ-M Futures "Mark Price" documentation; Kleppmann, M. (2017). <i>Designing
     * Data-Intensive Applications</i>. O'Reilly, ch. 7.
     */
    private Optional<BigDecimal> closingMark(PairListing pair) {
        return market.currentMarkPrice(pair.symbol()).or(() -> market.latestStoredMarkPrice(pair.pairId()));
    }

    private PaperAccount account(UUID userId) {
        return accounts.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("account " + userId + " is not stored"));
    }

    /** Closes the whole position at the mark with a system MARKET order, as a taker. */
    private void close(PaperAccount account, FuturesPosition position, BigDecimal mark, Instant at) {
        OrderSide side = position.getDirection().closingSide();
        PaperOrder order = orders.save(PaperOrder.futuresMarket(
                account.userId(),
                position.getPairId(),
                FuturesLiquidation.closingKey(SYSTEM_CLOSE, at, position.getId()),
                side,
                position.getPositionSide(),
                position.getQuantity(),
                // Hedge mode takes no reduce-only: its closing side already closes (TR-14b).
                position.getPositionSide() == PositionSide.BOTH));
        settlement.execute(
                account,
                order,
                mark,
                position.getQuantity(),
                Liquidity.TAKER,
                FillSource.LIVE,
                at,
                PositionCloseReason.SYSTEM);
        tpSl.settle(account, order, at);
    }
}
