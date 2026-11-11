package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.paper.dto.response.BalanceUpdateResponse;
import com.cryptopilot.paper.dto.response.FuturesAccountResponse;
import com.cryptopilot.paper.dto.response.FuturesConfigUpdateResponse;
import com.cryptopilot.paper.dto.response.FuturesMarginCallResponse;
import com.cryptopilot.paper.dto.response.FuturesOrderUpdateResponse;
import com.cryptopilot.paper.dto.response.FuturesTradeResponse;
import com.cryptopilot.paper.entity.PaperBalance;
import com.cryptopilot.paper.entity.PaperFill;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.WalletType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * on {@code /user/queue/balances}, in that order, once the transaction has committed. A paper Futures order is pushed
 * with the fills the transaction made on {@code /user/queue/futures.orders}, and the Futures account the transaction
 * changed on {@code /user/queue/futures.account}, as the exchange's {@code ORDER_TRADE_UPDATE} and
 * {@code ACCOUNT_UPDATE} (TR-41); the Spot queues never carry a Futures order or fill. A Futures conditional order goes
 * on {@code /user/queue/futures.algo} instead ({@code ALGO_UPDATE}), a change of the settings on
 * {@code /user/queue/futures.config} ({@code ACCOUNT_CONFIG_UPDATE}) and a margin call on
 * {@code /user/queue/futures.marginCall} ({@code MARGIN_CALL}) (TR-43).
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
 * <p>Rule: TR-08, TR-41, TR-43; TECHNICAL_DESIGN 9.
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

    /** The queue of each paper Futures order whose state changed, with the fills that changed it. */
    static final String FUTURES_ORDERS = "/queue/futures.orders";

    /** The queue of the paper Futures account, after a change of its wallet, a position or an order. */
    static final String FUTURES_ACCOUNT = "/queue/futures.account";

    /** The queue of each paper Futures conditional order whose state changed (TR-43). */
    static final String FUTURES_ALGO = "/queue/futures.algo";

    /** The queue of each change of the paper Futures settings (TR-43). */
    static final String FUTURES_CONFIG = "/queue/futures.config";

    /** The queue of the paper Futures margin calls (TR-43). */
    static final String FUTURES_MARGIN_CALL = "/queue/futures.marginCall";

    /** The key the changes noted in a transaction are bound to. */
    private static final Object PENDING = new Object();

    private static final Logger log = LoggerFactory.getLogger(PaperUserFeed.class);

    private final SimpMessageSendingOperations broker;
    private final MarketApi market;
    private final FuturesAccountServiceImpl futuresAccounts;

    /** Notes that an order of the account changed. */
    void order(PaperAccount account, PaperOrder order) {
        Pending pending = pending();
        if (pending != null) {
            pending.orders.put(order.getId(), new Owned<>(account.userId(), order));
        }
    }

    /** Notes a new fill of the account. */
    void fill(PaperAccount account, PaperFill fill) {
        Pending pending = pending();
        if (pending != null) {
            pending.fills.put(fill.getId(), new Owned<>(account.userId(), fill));
        }
    }

    /** Notes that a balance of the account moved; {@code asset} is the coin's symbol. */
    void balance(PaperAccount account, PaperBalance balance, String asset) {
        Pending pending = pending();
        if (pending != null) {
            BalanceKey key = new BalanceKey(balance.getUserId(), balance.getWalletType(), balance.getCoinId());
            pending.balances.put(key, new Owned<>(account.userId(), new Moved(balance, asset)));
        }
    }

    /** Notes that a Futures order of the account changed. */
    void futuresOrder(PaperAccount account, PaperOrder order) {
        Pending pending = pending();
        if (pending != null) {
            (order.isConditional() ? pending.futuresAlgos : pending.futuresOrders)
                    .put(order.getId(), new Owned<>(account.userId(), order));
            pending.futuresAccounts.add(account.userId());
        }
    }

    /** Notes a change of the Trader's Futures settings (TR-43). */
    void futuresConfig(UUID userId, FuturesConfigUpdateResponse change) {
        Pending pending = pending();
        if (pending != null) {
            pending.direct.add(new Message(userId, FUTURES_CONFIG, change));
        }
    }

    /** Notes a margin call of the Trader (TR-43). */
    void marginCall(UUID userId, FuturesMarginCallResponse call) {
        Pending pending = pending();
        if (pending != null) {
            pending.direct.add(new Message(userId, FUTURES_MARGIN_CALL, call));
        }
    }

    /** Notes a new fill of a Futures order of the account; its order is noted with it. */
    void futuresFill(PaperAccount account, PaperOrder order, PaperFill fill) {
        Pending pending = pending();
        if (pending != null) {
            pending.futuresFills.put(fill.getId(), new Owned<>(account.userId(), fill));
        }
        futuresOrder(account, order);
    }

    /** Notes that the Futures account changed without an order: a transfer, a margin change, a leverage. */
    void futuresAccount(PaperAccount account) {
        Pending pending = pending();
        if (pending != null) {
            pending.futuresAccounts.add(account.userId());
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
            pending.futuresOrders
                    .values()
                    .forEach(owned -> pairIds.add(owned.row().getPairId()));
            pending.futuresAlgos
                    .values()
                    .forEach(owned -> pairIds.add(owned.row().getPairId()));
            pending.fills.values().forEach(owned -> pairIds.add(owned.row().getPairId()));
            Map<UUID, String> symbols = pairIds.isEmpty()
                    ? Map.of()
                    : market.pairListings(pairIds).stream()
                            .collect(Collectors.toMap(PairListing::pairId, PairListing::symbol, (a, b) -> a));
            Set<UUID> feeCoins = new HashSet<>();
            pending.fills.values().forEach(owned -> feeCoins.add(owned.row().getFeeCoinId()));
            pending.futuresFills
                    .values()
                    .forEach(owned -> feeCoins.add(owned.row().getFeeCoinId()));
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
            pending.futuresOrders.values().forEach(owned -> {
                String symbol = symbols.get(owned.row().getPairId());
                List<FuturesTradeResponse> trades = pending.futuresFills.values().stream()
                        .filter(fill ->
                                fill.row().getOrderId().equals(owned.row().getId()))
                        .map(fill -> PaperResponses.futuresTrade(
                                fill.row(), symbol, assets.get(fill.row().getFeeCoinId())))
                        .toList();
                messages.add(new Message(
                        owned.userId(),
                        FUTURES_ORDERS,
                        new FuturesOrderUpdateResponse(PaperResponses.futuresOrder(owned.row(), symbol), trades)));
            });
            pending.futuresAlgos
                    .values()
                    .forEach(owned -> messages.add(new Message(
                            owned.userId(),
                            FUTURES_ALGO,
                            PaperResponses.futuresAlgo(
                                    owned.row(), symbols.get(owned.row().getPairId()), null))));
            messages.addAll(pending.direct);
            for (UUID userId : pending.futuresAccounts) {
                futuresAccount(userId).ifPresent(body -> messages.add(new Message(userId, FUTURES_ACCOUNT, body)));
            }
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

    /**
     * The Futures account as the transaction leaves it; empty when it cannot be valued now, e.g. without a mark price of
     * a pair with a position, so the other pushes of the transaction still go.
     */
    private Optional<FuturesAccountResponse> futuresAccount(UUID userId) {
        try {
            return Optional.of(futuresAccounts.accountOf(userId));
        } catch (RuntimeException unavailable) {
            log.debug("TR-41 Futures account of user {} not pushed: {}", userId, unavailable.toString());
            return Optional.empty();
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
        private final Map<UUID, Owned<PaperOrder>> futuresOrders = new LinkedHashMap<>();
        private final Map<UUID, Owned<PaperFill>> futuresFills = new LinkedHashMap<>();
        private final Map<UUID, Owned<PaperOrder>> futuresAlgos = new LinkedHashMap<>();
        private final List<Message> direct = new ArrayList<>();
        private final Set<UUID> futuresAccounts = new LinkedHashSet<>();
        private List<Message> messages = List.of();
    }

    private record Owned<T>(UUID userId, T row) {}

    private record Moved(PaperBalance balance, String asset) {}

    private record BalanceKey(UUID userId, WalletType wallet, UUID coinId) {}

    private record Message(UUID userId, String queue, Object body) {}
}
