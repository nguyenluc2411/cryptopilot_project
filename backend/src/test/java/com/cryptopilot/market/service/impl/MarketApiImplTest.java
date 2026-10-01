package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.CachedPrice;
import com.cryptopilot.market.model.PriceLookup;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.LeverageBracketRepository;
import com.cryptopilot.market.service.PriceCacheService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** What the market module tells a plan: only a pair it may be traded on, and only a current price. */
class MarketApiImplTest {

    private static final PairFilters FILTERS =
            new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5"));

    private final CryptoPairRepository pairs = mock(CryptoPairRepository.class);
    private final LeverageBracketRepository brackets = mock(LeverageBracketRepository.class);
    private final PriceCacheService prices = mock(PriceCacheService.class);
    private final MarketApiImpl api = new MarketApiImpl(pairs, brackets, prices);

    @Test
    void BR07_aPairEnabledOnTheMarketWithItsFilters_isTradable() {
        CryptoPair pair = pair();
        pair.applyFilters(MarketType.SPOT, FILTERS);
        pair.enable(MarketType.SPOT);
        when(pairs.findById(pair.getId())).thenReturn(Optional.of(pair));

        assertThat(api.tradablePair(pair.getId(), MarketType.SPOT))
                .contains(new TradablePair(pair.getId(), "BTCUSDT", MarketType.SPOT, FILTERS));
    }

    @Test
    void BR07_aPairNotEnabledOnTheMarket_isNotTradableThere() {
        CryptoPair pair = pair();
        pair.applyFilters(MarketType.FUTURES, FILTERS);
        pair.enable(MarketType.SPOT);
        when(pairs.findById(pair.getId())).thenReturn(Optional.of(pair));

        assertThat(api.tradablePair(pair.getId(), MarketType.FUTURES)).isEmpty();
    }

    @Test
    void anEnabledPairWhoseFiltersAreNotKnown_cannotBeSizedSoIsNotTradable() {
        CryptoPair pair = pair();
        pair.enable(MarketType.SPOT);
        when(pairs.findById(pair.getId())).thenReturn(Optional.of(pair));

        assertThat(api.tradablePair(pair.getId(), MarketType.SPOT)).isEmpty();
    }

    @Test
    void anUnknownPair_isNotTradable() {
        UUID unknown = UUID.randomUUID();
        when(pairs.findById(unknown)).thenReturn(Optional.empty());

        assertThat(api.tradablePair(unknown, MarketType.SPOT)).isEmpty();
    }

    @Test
    void BR33_aCurrentPrice_givesItsLastPriceAndFundingRate() {
        when(prices.latest(MarketType.FUTURES, "BTCUSDT")).thenReturn(found(price("101", "0.0001")));

        assertThat(api.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).contains(new BigDecimal("101"));
        assertThat(api.currentFundingRate("BTCUSDT")).contains(new BigDecimal("0.0001"));
    }

    @Test
    void BR33_aCurrentEntryWithoutTheValue_givesNothing() {
        when(prices.latest(MarketType.FUTURES, "BTCUSDT")).thenReturn(found(price(null, null)));

        assertThat(api.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).isEmpty();
        assertThat(api.currentFundingRate("BTCUSDT")).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = PriceLookup.Status.class,
            names = {"EXPIRED", "MISSING", "UNAVAILABLE"})
    void NSF03_aPriceThatIsNotCurrent_isNeverReturnedAsOne(PriceLookup.Status status) {
        PriceLookup lookup = new PriceLookup(
                status, status == PriceLookup.Status.EXPIRED ? Optional.of(price("101", "0.0001")) : Optional.empty());
        when(prices.latest(MarketType.SPOT, "BTCUSDT")).thenReturn(lookup);

        assertThat(api.currentLastPrice(MarketType.SPOT, "BTCUSDT")).isEmpty();
    }

    @Test
    void BR27_theBrackets_areTheStoredOnes() {
        UUID pairId = UUID.randomUUID();
        List<LeverageTier> stored = List.of(new LeverageTier(
                1, BigDecimal.ZERO, new BigDecimal("50000"), 125, new BigDecimal("0.004"), BigDecimal.ZERO));
        when(brackets.findByPair(pairId)).thenReturn(stored);

        assertThat(api.leverageBrackets(pairId)).isEqualTo(stored);
    }

    private static CryptoPair pair() {
        return CryptoPair.register(UUID.randomUUID(), UUID.randomUUID(), "BTCUSDT");
    }

    private static PriceLookup found(CachedPrice price) {
        return new PriceLookup(PriceLookup.Status.FOUND, Optional.of(price));
    }

    private static CachedPrice price(String last, String funding) {
        return new CachedPrice(
                MarketType.FUTURES,
                "BTCUSDT",
                last == null ? null : new BigDecimal(last),
                null,
                null,
                null,
                null,
                funding == null ? null : new BigDecimal(funding),
                null,
                Instant.parse("2026-10-01T08:00:00Z"));
    }
}
