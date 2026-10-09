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
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    private final PaperBalanceRepository balances;
    private final PaperLedgerEntryRepository ledger;

    /**
     * The opening grant of a coin (Q-T5): a new balance holding {@code amount}, written without looking for one first,
     * because a new account holds nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaperBalance grant(PaperAccount account, WalletType wallet, CoinListing coin, BigDecimal amount, Ref ref) {
        PaperBalance balance = balances.save(PaperBalance.empty(account.getId(), wallet, coin.coinId()));
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
        PaperBalance balance = balances.findByAccountIdAndWalletTypeAndCoinId(account.getId(), wallet, coin.coinId())
                .orElseGet(() -> balances.save(PaperBalance.empty(account.getId(), wallet, coin.coinId())));
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
        PaperBalance balance = balances.findByAccountIdAndWalletTypeAndCoinId(account.getId(), wallet, coin.coinId())
                .orElseGet(() -> PaperBalance.empty(account.getId(), wallet, coin.coinId()));
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
        PaperBalance balance = balances.findByAccountIdAndWalletTypeAndCoinId(account.getId(), wallet, coin.coinId())
                .orElseGet(() -> PaperBalance.empty(account.getId(), wallet, coin.coinId()));
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
        return balances.findByAccountIdAndWalletTypeAndCoinId(account.getId(), wallet, coin.coinId())
                .orElseThrow(() -> new IllegalStateException(
                        wallet + " " + coin.symbol() + " of account " + account.getId() + " holds nothing locked"));
    }

    private void record(PaperBalance balance, LedgerEntryType type, BigDecimal amount, Ref ref) {
        ledger.save(PaperLedgerEntry.of(balance, type, amount, ref));
    }
}
