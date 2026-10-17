package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.config.PaperEquityProperties;
import com.cryptopilot.paper.entity.PaperAccount;
import com.cryptopilot.paper.entity.PaperBalance;
import com.cryptopilot.paper.entity.PaperEquitySnapshot;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.PaperBalanceRepository;
import com.cryptopilot.paper.repository.PaperEquitySnapshotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a paper account is worth in USDT (TR-13): each wallet's balances at the current Spot last prices, USDT at par.
 * A coin without a current price is left out, as the wallet screen leaves it out of its estimate. Takes the daily
 * snapshot of that worth: once per account and day, in the zone of {@link PaperEquityProperties}.
 *
 * <p>Rule: TR-13.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperEquity {

    private final PaperBalanceRepository balances;
    private final PaperEquitySnapshotRepository snapshots;
    private final MarketApi market;
    private final PaperEquityProperties properties;
    private final Clock clock;

    /** The day it is now, in the zone the snapshots are taken in. */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), properties.zone());
    }

    /** What the account's two wallets are worth now. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Worth worth(UUID accountId) {
        List<PaperBalance> held = balances.findByAccountId(accountId);
        Map<UUID, String> assets =
                market.coins(held.stream().map(PaperBalance::getCoinId).collect(Collectors.toSet())).stream()
                        .collect(Collectors.toMap(CoinListing::coinId, CoinListing::symbol, (a, b) -> a));
        Set<String> pairs = held.stream()
                .map(balance -> assets.get(balance.getCoinId()))
                .filter(asset -> asset != null && !PaperAccountServiceImpl.USDT.equals(asset))
                .map(asset -> asset + PaperAccountServiceImpl.USDT)
                .collect(Collectors.toSet());
        Map<String, BigDecimal> prices = pairs.isEmpty() ? Map.of() : market.currentLastPrices(MarketType.SPOT, pairs);
        Map<WalletType, BigDecimal> byWallet = held.stream()
                .collect(Collectors.groupingBy(
                        PaperBalance::getWalletType,
                        Collectors.mapping(
                                balance -> valueOf(assets.get(balance.getCoinId()), balance.total(), prices),
                                Collectors.reducing(BigDecimal.ZERO, Function.identity(), BigDecimal::add))));
        return new Worth(
                Rounding.toAmount(byWallet.getOrDefault(WalletType.SPOT, BigDecimal.ZERO)),
                Rounding.toAmount(byWallet.getOrDefault(WalletType.FUTURES, BigDecimal.ZERO)));
    }

    /** Takes today's snapshot of the account, unless one was taken already. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void snapshot(PaperAccount account) {
        snapshot(account.getId());
    }

    /** Takes today's snapshot of the account, unless one was taken already; whether this call took it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean snapshot(UUID accountId) {
        LocalDate day = today();
        if (snapshots.findByAccountIdAndSnapshotDate(accountId, day).isPresent()) {
            return false;
        }
        Worth worth = worth(accountId);
        snapshots.save(PaperEquitySnapshot.of(accountId, day, worth.spot(), worth.futures()));
        return true;
    }

    private static BigDecimal valueOf(String asset, BigDecimal amount, Map<String, BigDecimal> prices) {
        if (asset == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = PaperAccountServiceImpl.usdtValue(asset, amount, prices);
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * What the two wallets of an account are worth, in USDT.
     *
     * @param spot the Spot wallet
     * @param futures the Futures wallet
     */
    public record Worth(BigDecimal spot, BigDecimal futures) {

        public BigDecimal total() {
            return spot.add(futures);
        }
    }
}
