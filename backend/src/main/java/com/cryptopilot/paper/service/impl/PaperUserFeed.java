package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.paper.dto.response.BalanceUpdateResponse;
import com.cryptopilot.paper.entity.PaperAccount;
import com.cryptopilot.paper.entity.PaperBalance;
import com.cryptopilot.paper.entity.PaperFill;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.model.enums.WalletType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Pushes what a transaction changed in a Trader's paper account to that Trader's user queues (TR-08): each order whose
 * state changed on {@code /user/queue/orders}, each new fill on {@code /user/queue/fills}, and each balance that moved
 * on {@code /user/queue/balances}, in that order, once the transaction has committed.
 *
 * <h2>Only what was committed</h2>
 *
 * <p>The changes are noted while the transaction runs and their bodies are written just before it commits, from the
 * rows as the transaction leaves them; they are sent only after the commit. A placement refused or rolled back pushes
 * nothing, and a client never sees a fill the database does not hold. An order that changed several times in one
 * transaction — locked, executed twice, filled — is pushed once, as it ended.
 *
 * <h2>Never at the cost of a trade</h2>
 *
 * <p>Writing or sending a push is not part of the trade: a failure is logged and the push dropped, and the transaction
 * commits as it would have without it. A Trader who misses a push sees the change on the next read, as before TR-08.
 *
 * <p>Runs inside the caller's transaction. Outside one there is nothing to wait for, so nothing is noted.
 *
 * <p>Rule: TR-08; TECHNICAL_DESIGN 9.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperUserFeed {

    /** The queue of each order whose state changed. */
    static final String ORDERS = "/queue/orders";

    /** The queue of each new fill. */
    static final String FILLS = "/queue/fills";

    /** The queue of each balance that moved. */
    static final String BALANCES = "/queue/balances";

    /** The key the changes noted in a transaction are bound to. */
    private static final Object PENDING = new Object();

    private static final Logger log = LoggerFactory.getLogger(PaperUserFeed.class);

    private final SimpMessageSendingOperations broker;
    private final MarketApi market;

    /** Notes that an order of the account changed. */
    void order(PaperAccount account, PaperOrder order) {
        Pending pending = pending();
        if (pending != null) {
            pending.orders.put(order.getId(), new Owned<>(account.getUserId(), order));
        }
    }

    /** Notes a new fill of the account. */
    void fill(PaperAccount account, PaperFill fill) {
        Pending pending = pending();
        if (pending != null) {
            pending.fills.put(fill.getId(), new Owned<>(account.getUserId(), fill));
        }
    }

    /** Notes that a balance of the account moved; {@code asset} is the coin's symbol. */
    void balance(PaperAccount account, PaperBalance balance, String asset) {
        Pending pending = pending();
        if (pending != null) {
            BalanceKey key = new BalanceKey(balance.getAccountId(), balance.getWalletType(), balance.getCoinId());
            pending.balances.put(key, new Owned<>(account.getUserId(), new Moved(balance, asset)));
        }
    }

    /** The changes noted in the current transaction, registering the push the first time; none outside one. */
    private Pending pending() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }
        Pending pending = (Pending) TransactionSynchronizationManager.getResource(PENDING);
        if (pending == null) {
            Pending fresh = new Pending();
            TransactionSynchronizationManager.bindResource(PENDING, fresh);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    fresh.messages = write(fresh);
                }

                @Override
                public void afterCommit() {
                    fresh.messages.forEach(PaperUserFeed.this::send);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING);
                }
            });
            pending = fresh;
        }
        return pending;
    }

    /** The bodies of the noted changes, written from the rows as the transaction leaves them. */
    private List<Message> write(Pending pending) {
        try {
            Set<UUID> pairIds = new HashSet<>();
            pending.orders.values().forEach(owned -> pairIds.add(owned.row().getPairId()));
            pending.fills.values().forEach(owned -> pairIds.add(owned.row().getPairId()));
            Map<UUID, String> symbols = pairIds.isEmpty()
                    ? Map.of()
                    : market.pairListings(pairIds).stream()
                            .collect(Collectors.toMap(PairListing::pairId, PairListing::symbol, (a, b) -> a));
            Set<UUID> feeCoins = pending.fills.values().stream()
                    .map(owned -> owned.row().getFeeCoinId())
                    .collect(Collectors.toSet());
            Map<UUID, String> assets = feeCoins.isEmpty()
                    ? Map.of()
                    : market.coins(feeCoins).stream()
                            .collect(Collectors.toMap(CoinListing::coinId, CoinListing::symbol, (a, b) -> a));

            List<Message> messages = new ArrayList<>();
            pending.orders
                    .values()
                    .forEach(owned -> messages.add(new Message(
                            owned.userId(),
                            ORDERS,
                            PaperResponses.order(
                                    owned.row(), symbols.get(owned.row().getPairId())))));
            pending.fills
                    .values()
                    .forEach(owned -> messages.add(new Message(
                            owned.userId(),
                            FILLS,
                            PaperResponses.fill(
                                    owned.row(),
                                    symbols.get(owned.row().getPairId()),
                                    assets.get(owned.row().getFeeCoinId())))));
            pending.balances.values().forEach(owned -> {
                PaperBalance balance = owned.row().balance();
                messages.add(new Message(
                        owned.userId(),
                        BALANCES,
                        new BalanceUpdateResponse(
                                balance.getWalletType(),
                                balance.getCoinId(),
                                owned.row().asset(),
                                Rounding.toAmount(balance.getFreeAmount()),
                                Rounding.toAmount(balance.getLockedAmount()),
                                Rounding.toAmount(balance.total()))));
            });
            return messages;
        } catch (RuntimeException failure) {
            log.warn("TR-08 paper pushes of a transaction could not be written: {}", failure.toString());
            return List.of();
        }
    }

    private void send(Message message) {
        try {
            broker.convertAndSendToUser(message.userId().toString(), message.queue(), message.body());
        } catch (RuntimeException failure) {
            log.warn(
                    "TR-08 paper push to {} of user {} failed: {}",
                    message.queue(),
                    message.userId(),
                    failure.toString());
        }
    }

    /** What one transaction changed, keyed so that a row changed twice is pushed once, in the order first noted. */
    private static final class Pending {
        private final Map<UUID, Owned<PaperOrder>> orders = new LinkedHashMap<>();
        private final Map<UUID, Owned<PaperFill>> fills = new LinkedHashMap<>();
        private final Map<BalanceKey, Owned<Moved>> balances = new LinkedHashMap<>();
        private List<Message> messages = List.of();
    }

    private record Owned<T>(UUID userId, T row) {}

    private record Moved(PaperBalance balance, String asset) {}

    private record BalanceKey(UUID accountId, WalletType wallet, UUID coinId) {}

    private record Message(UUID userId, String queue, Object body) {}
}
