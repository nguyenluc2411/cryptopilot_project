package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.CachedPrice;
import com.cryptopilot.market.model.PriceLookup;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.LeverageBracketRepository;
import com.cryptopilot.market.service.MinuteKlineService;
import com.cryptopilot.market.service.PriceCacheService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@link MarketApi} other modules read: pairs and brackets from the database, prices from the latest-price cache.
 *
 * <p>Rule: BR-07, BR-26, BR-27, BR-29, BR-33; NSF-03.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class MarketApiImpl implements MarketApi {

    private final CryptoPairRepository pairs;
    private final LeverageBracketRepository brackets;
    private final PriceCacheService prices;
    private final MinuteKlineService minuteKlines;

    @Override
    @Transactional(readOnly = true)
    public Optional<TradablePair> tradablePair(UUID pairId, MarketType market) {
        return pairs.findById(pairId).filter(pair -> pair.isEnabledOn(market)).flatMap(pair -> pair.filters(market)
                .map(filters -> new TradablePair(pair.getId(), pair.getSymbol(), market, filters)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PairListing> pairListings(Collection<UUID> pairIds) {
        if (pairIds.isEmpty()) {
            return List.of();
        }
        return pairs.findAllByIdIn(pairIds).stream()
                .map(pair -> new PairListing(
                        pair.getId(),
                        pair.getSymbol(),
                        pair.isEnabledOn(MarketType.SPOT),
                        pair.isEnabledOn(MarketType.FUTURES)))
                .toList();
    }

    @Override
    public Optional<BigDecimal> currentLastPrice(MarketType market, String symbol) {
        return current(market, symbol, CachedPrice::lastPrice);
    }

    @Override
    public Optional<BigDecimal> currentFundingRate(String symbol) {
        return current(MarketType.FUTURES, symbol, CachedPrice::fundingRate);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LeverageTier> leverageBrackets(UUID pairId) {
        return brackets.findByPair(pairId);
    }

    private Optional<BigDecimal> current(MarketType market, String symbol, Function<CachedPrice, BigDecimal> field) {
        PriceLookup lookup = prices.latest(market, symbol);
        if (lookup.status() != PriceLookup.Status.FOUND) {
            return Optional.empty();
        }
        return lookup.price().map(field);
    }

    @Override
    public MinuteKlineBatch closedMinuteKlines(MarketType market, UUID pairId, Instant from) {
        return minuteKlines.closedMinuteKlines(market, pairId, from);
    }
}
