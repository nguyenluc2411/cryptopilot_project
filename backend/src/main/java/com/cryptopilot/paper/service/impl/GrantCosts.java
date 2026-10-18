package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.paper.entity.PaperAccount;
import com.cryptopilot.paper.entity.PaperBalance;
import com.cryptopilot.paper.model.UsdtRate;
import com.cryptopilot.paper.model.enums.LedgerEntryType;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.PaperAccountRepository;
import com.cryptopilot.paper.repository.PaperBalanceRepository;
import com.cryptopilot.paper.repository.PaperLedgerEntryRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Values the granted coins that had no price when their account opened (PT-R13): a Spot balance whose cost is not
 * known and that nothing but the grant has changed gets the cost its whole amount is worth at the coin's USDT price now,
 * as the opening would have given it. A balance a fill or a transfer has changed is left as it is.
 *
 * <p>Each balance is valued in its own transaction under its Trader's paper lock, and checked again once the lock is
 * held, so a fill that came first is seen.
 *
 * <p>Rule: TR-13; PT-R13.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class GrantCosts {

    private static final Logger log = LoggerFactory.getLogger(GrantCosts.class);

    private final PaperBalanceRepository balances;
    private final PaperLedgerEntryRepository ledger;
    private final PaperAccountRepository accounts;
    private final MarketApi market;
    private final QuoteUsdtRate usdtRates;
    private final UserLock lock;
    private final TransactionTemplate transactions;

    /** Values every granted coin still without a cost that has a price now; how many it valued. */
    public int valueAll() {
        List<PaperBalance> found =
                balances.findWithoutCostChangedOnlyBy(WalletType.SPOT, LedgerEntryType.INITIAL_GRANT);
        if (found.isEmpty()) {
            return 0;
        }
        Map<UUID, CoinListing> coins =
                market.coins(found.stream().map(PaperBalance::getCoinId).collect(Collectors.toSet())).stream()
                        .collect(Collectors.toMap(CoinListing::coinId, Function.identity()));
        int valued = 0;
        for (PaperBalance balance : found) {
            CoinListing coin = coins.get(balance.getCoinId());
            if (coin == null || QuoteUsdtRate.isUsdt(coin)) {
                continue;
            }
            try {
                if (Boolean.TRUE.equals(transactions.execute(status -> value(balance.getAccountId(), coin)))) {
                    valued++;
                }
            } catch (RuntimeException failure) {
                log.warn(
                        "PT-R13 grant cost of {} in paper account {} not set: {}",
                        coin.symbol(),
                        balance.getAccountId(),
                        failure.toString());
            }
        }
        return valued;
    }

    private boolean value(UUID accountId, CoinListing coin) {
        PaperAccount account = accounts.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("account " + accountId + " is not stored"));
        lock.lock(PaperAccountServiceImpl.LOCK_SCOPE, account.getUserId());
        PaperBalance balance = balances.findByAccountIdAndWalletTypeAndCoinId(accountId, WalletType.SPOT, coin.coinId())
                .orElseThrow(() -> new IllegalStateException("balance of " + coin.symbol() + " is not stored"));
        boolean grantOnly = !ledger.existsByAccountIdAndWalletTypeAndCoinIdAndEntryTypeNot(
                accountId, WalletType.SPOT, coin.coinId(), LedgerEntryType.INITIAL_GRANT);
        if (balance.getCostBasisAmount() != null
                || !grantOnly
                || balance.total().signum() == 0) {
            return false;
        }
        UsdtRate price = usdtRates.current(coin);
        if (!price.known()) {
            return false;
        }
        balance.valueUnknownCost(price.valueOf(balance.total()));
        balances.save(balance);
        log.info("PT-R13 granted {} of paper account {} valued at {}", coin.symbol(), accountId, price.rate());
        return true;
    }
}
