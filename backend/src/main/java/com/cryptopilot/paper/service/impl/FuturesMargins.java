package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.paper.calculator.FuturesMarginMath;
import com.cryptopilot.paper.config.PaperAccountProperties;
import com.cryptopilot.paper.entity.FuturesAccountConfig;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.entity.PaperBalance;
import com.cryptopilot.paper.model.FuturesCrossState;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.WalletType;
import com.cryptopilot.paper.repository.FuturesAccountConfigRepository;
import com.cryptopilot.paper.repository.PaperBalanceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads what the Futures rules need from the database and the price caches: the Trader's Futures settings row
 * (created on first use), the mark prices of the pairs with positions, and the cross side of the account. Callers hold
 * the Trader's lock, so what they read here is what they write against.
 *
 * <p>Rule: TR-14, TR-18; D-127.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class FuturesMargins {

    private final FuturesAccountConfigRepository accountConfigs;
    private final PaperBalanceRepository balances;
    private final MarketApi market;
    private final EntityManager entityManager;

    /** The Trader's Futures settings, created with the defaults before the first Futures action. */
    FuturesAccountConfig accountConfig(UUID userId) {
        return accountConfigs
                .findById(userId)
                .orElseGet(() -> accountConfigs.save(FuturesAccountConfig.defaults(userId)));
    }

    /**
     * Raises the Futures account version at commit, after a change of the account (its wallet, a position or an
     * isolated wallet), so a snapshot cached before it is known to be stale. Forced, because the settings row itself
     * may not have changed.
     */
    void recordChange(FuturesAccountConfig config) {
        entityManager.lock(config, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    }

    /**
     * The current mark price of each pair of the positions.
     *
     * @throws BusinessException {@code MARKET_PRICE_UNAVAILABLE} when a pair has no mark price, so no margin can be
     *     computed
     */
    Map<UUID, BigDecimal> marks(Collection<FuturesPosition> positions) {
        Set<UUID> pairIds = positions.stream().map(FuturesPosition::getPairId).collect(Collectors.toSet());
        Map<UUID, BigDecimal> marks = new HashMap<>();
        for (PairListing pair : market.pairListings(pairIds)) {
            BigDecimal mark = market.currentMarkPrice(pair.symbol())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.MARKET_PRICE_UNAVAILABLE, "no mark price of " + pair.symbol() + " is known"));
            marks.put(pair.pairId(), mark);
        }
        return marks;
    }

    /**
     * The cross side of the account at the marks, from the open positions given.
     *
     * @param openOrderInitialMargin the margin of the open orders
     */
    FuturesCrossState crossState(
            UUID userId,
            List<FuturesPosition> openPositions,
            Map<UUID, BigDecimal> marks,
            BigDecimal openOrderInitialMargin) {
        BigDecimal wallet = futuresWallet(userId);
        BigDecimal isolated = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal initial = BigDecimal.ZERO;
        for (FuturesPosition position : openPositions) {
            if (position.getMarginType() == MarginType.ISOLATED) {
                isolated = isolated.add(position.getIsolatedWalletAmount());
                continue;
            }
            BigDecimal mark = marks.get(position.getPairId());
            unrealized = unrealized.add(FuturesMarginMath.unrealizedPnl(
                    position.getDirection(), position.getQuantity(), position.getEntryPrice(), mark));
            initial = initial.add(
                    FuturesMarginMath.initialMargin(position.getQuantity().multiply(mark), position.getLeverage()));
        }
        return new FuturesCrossState(wallet, wallet.subtract(isolated), unrealized, initial, openOrderInitialMargin);
    }

    private BigDecimal futuresWallet(UUID userId) {
        CoinListing usdt = market.coinsBySymbol(Set.of(PaperAccountProperties.FUTURES_COIN)).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Coin", PaperAccountProperties.FUTURES_COIN));
        return balances.findByUserIdAndWalletTypeAndCoinId(userId, WalletType.FUTURES, usdt.coinId())
                .map(PaperBalance::getFreeAmount)
                .orElse(BigDecimal.ZERO);
    }
}
