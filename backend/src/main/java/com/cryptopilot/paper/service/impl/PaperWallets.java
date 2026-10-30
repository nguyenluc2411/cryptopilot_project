package com.cryptopilot.paper.service.impl;

import com.cryptopilot.market.CoinListing;
import com.cryptopilot.paper.entity.PaperBalance;
import com.cryptopilot.paper.entity.PaperLedgerEntry;
import com.cryptopilot.paper.entity.PaperLedgerEntry.Ref;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.LedgerEntryType;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.PaperBalanceRepository;
import com.cryptopilot.paper.repository.PaperLedgerEntryRepository;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The only writer of paper balances. Every change of a balance's total is written with its ledger entry in the same
 * transaction, so the entries of a balance always add up to it; moving an amount between free and locked changes no
 * total and writes no entry.
 *
 * <p>Runs inside the caller's transaction, which holds the account's paper lock. A coin is passed with its symbol, so
 * a refusal names it to the Trader as {@code USDT}, never by its key. Each balance that moves is noted for the
 * Trader's balance queue ({@link PaperUserFeed}).
 *
 * <p>Rule: TR-04, TR-08.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperWallets {

    /** The key the balances read in a transaction are bound to. */
    private static final Object LOADED = new Object();

    private final PaperBalanceRepository balances;
    private final PaperLedgerEntryRepository ledger;
    private final PaperUserFeed feed;

    /**
     * The opening grant of a coin (Q-T5): a new balance holding {@code amount}, written without looking for one first,
     * because a new account holds nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaperBalance grant(PaperAccount account, WalletType wallet, CoinListing coin, BigDecimal amount, Ref ref) {
        PaperBalance balance = remember(balances.save(PaperBalance.empty(account.userId(), wallet, coin.coinId())));
        balance.credit(amount);
        record(balance, LedgerEntryType.INITIAL_GRANT, amount, ref);
        feed.balance(account, balance, coin.symbol());
        return balance;
    }

    /** Adds to the free amount of a coin, creating the balance the first time the wallet holds it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaperBalance credit(
            PaperAccount account,
            WalletType wallet,
            CoinListing coin,
            BigDecimal amount,
            LedgerEntryType type,
            Ref ref) {
        PaperBalance balance = find(account, wallet, coin)
                .orElseGet(() -> remember(balances.save(PaperBalance.empty(account.userId(), wallet, coin.coinId()))));
        balance.credit(amount);
        record(balance, type, amount, ref);
        feed.balance(account, balance, coin.symbol());
        return balance;
    }

    /**
     * Takes from the free amount of a coin.
     *
     * @throws com.cryptopilot.common.exception.BusinessException {@code PAPER_INSUFFICIENT_BALANCE} when less is
     *     free; nothing changes
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaperBalance debit(
            PaperAccount account,
            WalletType wallet,
            CoinListing coin,
            BigDecimal amount,
            LedgerEntryType type,
            Ref ref) {
        // A wallet that never held the coin has nothing free: the refusal comes from an empty balance that is never
        // stored, so a refused debit leaves no row behind.
        PaperBalance balance = find(account, wallet, coin)
                .orElseGet(() -> PaperBalance.empty(account.userId(), wallet, coin.coinId()));
        balance.debit(amount, coin.symbol());
        record(balance, type, amount.negate(), ref);
        feed.balance(account, balance, coin.symbol());
        return balance;
    }

    /**
     * Changes the Futures wallet by a signed amount and records it, creating the balance the first time: a realised
     * profit or loss, a clearance or funding fee, or an insurance cover. Unlike {@link #debit} it never refuses, as the
     * Futures wallet may go below zero while cross positions are open (D-127).
     *
     * @param pairId the pair of the position it comes from, which the income history shows (TR-19)
     * @return the ledger entry written
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaperLedgerEntry changeFutures(
            PaperAccount account, UUID pairId, CoinListing coin, BigDecimal amount, LedgerEntryType type, Ref ref) {
        PaperBalance balance = find(account, WalletType.FUTURES, coin)
                .orElseGet(() -> remember(
                        balances.save(PaperBalance.empty(account.userId(), WalletType.FUTURES, coin.coinId()))));
        balance.changeFutures(amount);
        PaperLedgerEntry entry =
                ledger.save(PaperLedgerEntry.of(balance, type, amount, ref).onPair(pairId));
        feed.balance(account, balance, coin.symbol());
        return entry;
    }

    /**
     * Holds back part of the free amount of a coin for a working order. The total does not change, so no ledger entry
     * is written.
     *
     * @throws com.cryptopilot.common.exception.BusinessException {@code PAPER_INSUFFICIENT_BALANCE} when less is
     *     free; nothing changes
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaperBalance lock(PaperAccount account, WalletType wallet, CoinListing coin, BigDecimal amount) {
        PaperBalance balance = find(account, wallet, coin)
                .orElseGet(() -> PaperBalance.empty(account.userId(), wallet, coin.coinId()));
        balance.lock(amount, coin.symbol());
        feed.balance(account, balance, coin.symbol());
        return balance;
    }

    /** Gives back an amount a working order held, for an order cancelled or ended. No ledger entry is written. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaperBalance unlock(PaperAccount account, WalletType wallet, CoinListing coin, BigDecimal amount) {
        PaperBalance balance = held(account, wallet, coin);
        balance.unlock(amount);
        feed.balance(account, balance, coin.symbol());
        return balance;
    }

    /** Takes an amount a working order held, for an order that filled. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaperBalance spendLocked(
            PaperAccount account,
            WalletType wallet,
            CoinListing coin,
            BigDecimal amount,
            LedgerEntryType type,
            Ref ref) {
        PaperBalance balance = held(account, wallet, coin);
        balance.spendLocked(amount);
        record(balance, type, amount.negate(), ref);
        feed.balance(account, balance, coin.symbol());
        return balance;
    }

    /** The free amount of a coin; zero for a coin the wallet never held. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal free(PaperAccount account, WalletType wallet, CoinListing coin) {
        return find(account, wallet, coin).map(PaperBalance::getFreeAmount).orElse(BigDecimal.ZERO);
    }

    // An amount was locked in this balance, so it exists; a missing one is a defect.
    private PaperBalance held(PaperAccount account, WalletType wallet, CoinListing coin) {
        return find(account, wallet, coin)
                .orElseThrow(() -> new IllegalStateException(
                        wallet + " " + coin.symbol() + " of account " + account.userId() + " holds nothing locked"));
    }

    /**
     * Reads the balances of these coins in one query and keeps them for the rest of the transaction, so the changes
     * that follow neither read them again nor make Hibernate flush the writes queued so far before each read: the
     * writes of an order then go to the database together, at commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void preload(PaperAccount account, WalletType wallet, CoinListing... coins) {
        Map<Key, Optional<PaperBalance>> held = loaded();
        List<UUID> unread = Arrays.stream(coins)
                .map(CoinListing::coinId)
                .distinct()
                .filter(coinId -> !held.containsKey(new Key(account.userId(), wallet, coinId)))
                .toList();
        if (unread.isEmpty()) {
            return;
        }
        // A coin the wallet has never held is remembered as absent, so it is not looked for again.
        unread.forEach(coinId -> held.put(new Key(account.userId(), wallet, coinId), Optional.empty()));
        balances.findByUserIdAndWalletTypeAndCoinIdIn(account.userId(), wallet, unread)
                .forEach(this::remember);
    }

    /**
     * A balance this transaction already read, else one read now. Only stored balances are kept: an empty one made up
     * for a refusal never is.
     */
    private Optional<PaperBalance> find(PaperAccount account, WalletType wallet, CoinListing coin) {
        Optional<PaperBalance> kept = loaded().get(new Key(account.userId(), wallet, coin.coinId()));
        if (kept != null) {
            return kept;
        }
        Optional<PaperBalance> found =
                balances.findByUserIdAndWalletTypeAndCoinId(account.userId(), wallet, coin.coinId());
        found.ifPresent(this::remember);
        return found;
    }

    private PaperBalance remember(PaperBalance balance) {
        loaded().put(new Key(balance.getUserId(), balance.getWalletType(), balance.getCoinId()), Optional.of(balance));
        return balance;
    }

    /** The balances read in the current transaction, empty for a coin known to be absent; dropped when it ends. */
    @SuppressWarnings("unchecked")
    private static Map<Key, Optional<PaperBalance>> loaded() {
        Map<Key, Optional<PaperBalance>> held =
                (Map<Key, Optional<PaperBalance>>) TransactionSynchronizationManager.getResource(LOADED);
        if (held == null) {
            Map<Key, Optional<PaperBalance>> fresh = new HashMap<>();
            TransactionSynchronizationManager.bindResource(LOADED, fresh);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(LOADED);
                }
            });
            held = fresh;
        }
        return held;
    }

    private record Key(UUID userId, WalletType wallet, UUID coinId) {}

    private void record(PaperBalance balance, LedgerEntryType type, BigDecimal amount, Ref ref) {
        ledger.save(PaperLedgerEntry.of(balance, type, amount, ref));
    }
}
