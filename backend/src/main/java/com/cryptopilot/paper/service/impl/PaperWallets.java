package com.cryptopilot.paper.service.impl;

import com.cryptopilot.market.CoinListing;
import com.cryptopilot.paper.entity.PaperAccount;
import com.cryptopilot.paper.entity.PaperBalance;
import com.cryptopilot.paper.entity.PaperLedgerEntry;
import com.cryptopilot.paper.entity.PaperLedgerEntry.Ref;
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
 * a refusal names it to the Trader as {@code USDT}, never by its key.
 *
 * <p>Rule: TR-04.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperWallets {

    /** The key the balances read in a transaction are bound to. */
    private static final Object LOADED = new Object();

    private final PaperBalanceRepository balances;
    private final PaperLedgerEntryRepository ledger;

    /**
     * The opening grant of a coin (Q-T5): a new balance holding {@code amount}, written without looking for one first,
     * because a new account holds nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaperBalance grant(PaperAccount account, WalletType wallet, CoinListing coin, BigDecimal amount, Ref ref) {
        PaperBalance balance = remember(balances.save(PaperBalance.empty(account.getId(), wallet, coin.coinId())));
        balance.credit(amount);
        record(balance, LedgerEntryType.INITIAL_GRANT, amount, ref);
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
                .orElseGet(() -> remember(balances.save(PaperBalance.empty(account.getId(), wallet, coin.coinId()))));
        balance.credit(amount);
        record(balance, type, amount, ref);
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
        PaperBalance balance =
                find(account, wallet, coin).orElseGet(() -> PaperBalance.empty(account.getId(), wallet, coin.coinId()));
        balance.debit(amount, coin.symbol());
        record(balance, type, amount.negate(), ref);
        return balance;
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
        PaperBalance balance =
                find(account, wallet, coin).orElseGet(() -> PaperBalance.empty(account.getId(), wallet, coin.coinId()));
        balance.lock(amount, coin.symbol());
        return balance;
    }

    /** Gives back an amount a working order held, for an order cancelled or ended. No ledger entry is written. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaperBalance unlock(PaperAccount account, WalletType wallet, CoinListing coin, BigDecimal amount) {
        PaperBalance balance = held(account, wallet, coin);
        balance.unlock(amount);
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
        return balance;
    }

    // An amount was locked in this balance, so it exists; a missing one is a defect.
    private PaperBalance held(PaperAccount account, WalletType wallet, CoinListing coin) {
        return find(account, wallet, coin)
                .orElseThrow(() -> new IllegalStateException(
                        wallet + " " + coin.symbol() + " of account " + account.getId() + " holds nothing locked"));
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
                .filter(coinId -> !held.containsKey(new Key(account.getId(), wallet, coinId)))
                .toList();
        if (unread.isEmpty()) {
            return;
        }
        // A coin the wallet has never held is remembered as absent, so it is not looked for again.
        unread.forEach(coinId -> held.put(new Key(account.getId(), wallet, coinId), Optional.empty()));
        balances.findByAccountIdAndWalletTypeAndCoinIdIn(account.getId(), wallet, unread)
                .forEach(this::remember);
    }

    /**
     * A balance this transaction already read, else one read now. Only stored balances are kept: an empty one made up
     * for a refusal never is.
     */
    private Optional<PaperBalance> find(PaperAccount account, WalletType wallet, CoinListing coin) {
        Optional<PaperBalance> kept = loaded().get(new Key(account.getId(), wallet, coin.coinId()));
        if (kept != null) {
            return kept;
        }
        Optional<PaperBalance> found =
                balances.findByAccountIdAndWalletTypeAndCoinId(account.getId(), wallet, coin.coinId());
        found.ifPresent(this::remember);
        return found;
    }

    private PaperBalance remember(PaperBalance balance) {
        loaded().put(
                        new Key(balance.getAccountId(), balance.getWalletType(), balance.getCoinId()),
                        Optional.of(balance));
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

    private record Key(UUID accountId, WalletType wallet, UUID coinId) {}

    private void record(PaperBalance balance, LedgerEntryType type, BigDecimal amount, Ref ref) {
        ledger.save(PaperLedgerEntry.of(balance, type, amount, ref));
    }
}
