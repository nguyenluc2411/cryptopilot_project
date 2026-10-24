package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.config.PaperEquityProperties;
import com.cryptopilot.paper.entity.PaperBalance;
import com.cryptopilot.paper.entity.PaperEquitySnapshot;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.PaperBalanceRepository;
import com.cryptopilot.paper.repository.PaperEquitySnapshotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a paper account is worth in USDT (TR-13): each wallet's balances at the current Spot last prices, USDT at par.
 * A coin without a current price is left out, as the wallet screen leaves it out of its estimate. Takes the daily
 * snapshot of that worth: once per account and day, in the zone of {@link PaperEquityProperties}, and only while every
 * coin held has a price, so a lost stream never stores a low worth as the start of the day.
 *
 * <p>Rule: TR-13; PT-R02.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaperEquity {

    private static final Logger log = LoggerFactory.getLogger(PaperEquity.class);

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
    public Worth worth(UUID userId) {
        List<PaperBalance> held = balances.findByUserId(userId);
        Map<UUID, String> assets =
                market.coins(held.stream().map(PaperBalance::getCoinId).collect(Collectors.toSet())).stream()
                        .collect(Collectors.toMap(CoinListing::coinId, CoinListing::symbol, (a, b) -> a));
        Set<String> pairs = held.stream()
                .map(balance -> assets.get(balance.getCoinId()))
                .filter(asset -> asset != null && !PaperAccountServiceImpl.USDT.equals(asset))
                .map(asset -> asset + PaperAccountServiceImpl.USDT)
                .collect(Collectors.toSet());
        Map<String, BigDecimal> prices = pairs.isEmpty() ? Map.of() : market.currentLastPrices(MarketType.SPOT, pairs);
        BigDecimal spot = BigDecimal.ZERO;
        BigDecimal futures = BigDecimal.ZERO;
        Set<String> unpriced = new TreeSet<>();
        for (PaperBalance balance : held) {
            String asset = assets.get(balance.getCoinId());
            BigDecimal value = asset == null ? null : PaperAccountServiceImpl.usdtValue(asset, balance.total(), prices);
            if (value == null) {
                unpriced.add(asset == null ? balance.getCoinId().toString() : asset);
            } else if (balance.getWalletType() == WalletType.SPOT) {
                spot = spot.add(value);
            } else {
                futures = futures.add(value);
            }
        }
        return new Worth(Rounding.toAmount(spot), Rounding.toAmount(futures), Set.copyOf(unpriced));
    }

    /** Takes today's snapshot of the account, unless one was taken already. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void snapshot(PaperAccount account) {
        snapshot(account.userId());
    }

    /**
     * Takes today's snapshot of the account, unless one was taken already or a coin held has no current price; whether
     * this call took it. One left untaken is taken by a later call of the same day.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean snapshot(UUID userId) {
        LocalDate day = today();
        if (snapshots.findByUserIdAndSnapshotDate(userId, day).isPresent()) {
            return false;
        }
        Worth worth = worth(userId);
        if (!worth.complete()) {
            log.debug("TR-13 no snapshot of paper account {} yet: no price of {}", userId, worth.unpriced());
            return false;
        }
        snapshots.save(PaperEquitySnapshot.of(userId, day, worth.spot(), worth.futures()));
        return true;
    }

    /**
     * What the two wallets of an account are worth, in USDT.
     *
     * @param spot the Spot wallet, the coins without a current price left out
     * @param futures the Futures wallet
     * @param unpriced the coins held, not zero, that have no current price
     */
    public record Worth(BigDecimal spot, BigDecimal futures, Set<String> unpriced) {

        /** Whether every coin held has a price, so the worth is the account's full worth. */
        public boolean complete() {
            return unpriced.isEmpty();
        }

        public BigDecimal total() {
            return spot.add(futures);
        }
    }
}
