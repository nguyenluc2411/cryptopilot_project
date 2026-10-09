package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.entity.Coin;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.CachedPrice;
import com.cryptopilot.market.model.PriceLookup;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.CoinRepository;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.repository.LeverageBracketRepository;
import com.cryptopilot.market.service.MinuteKlineService;
import com.cryptopilot.market.service.PriceCacheService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** What the market module tells a plan: only a pair it may be traded on, and only a current price. */
class MarketApiImplTest {

    private static final PairFilters FILTERS =
            new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5"));

    private final CryptoPairRepository pairs = mock(CryptoPairRepository.class);
    private final CoinRepository coins = mock(CoinRepository.class);
    private final CoinWriter coinWriter = mock(CoinWriter.class);
    private final LeverageBracketRepository brackets = mock(LeverageBracketRepository.class);
    private final PriceCacheService prices = mock(PriceCacheService.class);
    private final MinuteKlineService minuteKlines = mock(MinuteKlineService.class);
    private final MarketApiImpl api = new MarketApiImpl(pairs, coins, brackets, prices, minuteKlines, coinWriter);

    @Test
    void TR04_coins_areNamedBySymbol_andAnUnknownSymbolIsLeftOut() {
        Coin usdt = Coin.fromExchange("USDT");
        when(coins.findAllBySymbolIn(List.of("USDT", "NOPE"))).thenReturn(List.of(usdt));

        assertThat(api.coinsBySymbol(List.of("USDT", "NOPE")))
                .containsExactly(new CoinListing(usdt.getId(), "USDT", usdt.getCoinName()));
    }

    @Test
    void TR04_ensuringCoins_storesOnlyTheMissingOnes_andReturnsEveryOne() {
        Coin usdt = Coin.fromExchange("USDT");
        Coin btc = Coin.fromExchange("BTC");
        when(coins.findAllBySymbolIn(Set.of("BTC", "USDT")))
                .thenReturn(List.of(usdt))
                .thenReturn(List.of(usdt, btc));

        assertThat(api.ensureCoins(List.of("USDT", "BTC")))
                .extracting(CoinListing::symbol)
                .containsExactlyInAnyOrder("USDT", "BTC");
        verify(coinWriter).storeIfAbsent("BTC");
        verify(coinWriter, never()).storeIfAbsent("USDT");
    }

    @Test
    void TR04_batchPrices_keepOnlyTheCurrentOnes() {
        CachedPrice btc = new CachedPrice(
                MarketType.SPOT,
                "BTCUSDT",
                new BigDecimal("60000"),
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-10-07T00:00:00Z"));
        when(prices.latest(MarketType.SPOT, List.of("BTCUSDT", "ETHUSDT")))
                .thenReturn(Map.of(
                        "BTCUSDT",
                        new PriceLookup(PriceLookup.Status.FOUND, Optional.of(btc)),
                        "ETHUSDT",
                        PriceLookup.missing()));

        assertThat(api.currentLastPrices(MarketType.SPOT, List.of("BTCUSDT", "ETHUSDT")))
                .containsOnlyKeys("BTCUSDT")
                .containsEntry("BTCUSDT", new BigDecimal("60000"));
    }

    @Test
    void TR04_ensuringCoinsAlreadyStored_readsOnce_andStoresNothing() {
        Coin usdt = Coin.fromExchange("USDT");
        when(coins.findAllBySymbolIn(Set.of("USDT"))).thenReturn(List.of(usdt));

        assertThat(api.ensureCoins(List.of("USDT")))
                .extracting(CoinListing::symbol)
                .containsExactly("USDT");
        verify(coins).findAllBySymbolIn(Set.of("USDT"));
        verifyNoInteractions(coinWriter);
    }

    @Test
    void TR04_noSymbols_readNothing() {
        assertThat(api.coinsBySymbol(List.of())).isEmpty();
        assertThat(api.coins(List.of())).isEmpty();
        verifyNoInteractions(coins);
    }

    @Test
    void A04_closedMinuteCandles_areFetchedByTheMinuteKlineService() {
        UUID pairId = UUID.randomUUID();
        Instant from = Instant.parse("2026-10-03T08:00:00Z");
        MinuteKlineBatch batch = MinuteKlineBatch.of(List.of());
        when(minuteKlines.closedMinuteKlines(MarketType.FUTURES, pairId, from)).thenReturn(batch);

        assertThat(api.closedMinuteKlines(MarketType.FUTURES, pairId, from)).isSameAs(batch);
    }

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
    void BR07_aPairListing_namesTheMarketsThePairIsEnabledOn_andAnUnlistedPairIsStillReturned() {
        CryptoPair spotOnly = pair();
        spotOnly.enable(MarketType.SPOT);
        CryptoPair unlisted = CryptoPair.register(UUID.randomUUID(), UUID.randomUUID(), "ETHUSDT");
        List<UUID> ids = List.of(spotOnly.getId(), unlisted.getId(), UUID.randomUUID());
        when(pairs.findAllByIdIn(ids)).thenReturn(List.of(spotOnly, unlisted));

        assertThat(api.pairListings(ids))
                .containsExactlyInAnyOrder(
                        new PairListing(spotOnly.getId(), "BTCUSDT", true, false),
                        new PairListing(unlisted.getId(), "ETHUSDT", false, false));
    }

    @Test
    void TR02_aPairsCoins_areItsBaseAndQuote() {
        Coin btc = Coin.fromExchange("BTC");
        Coin usdt = Coin.fromExchange("USDT");
        CryptoPair pair = CryptoPair.register(btc.getId(), usdt.getId(), "BTCUSDT");
        when(pairs.findById(pair.getId())).thenReturn(Optional.of(pair));
        when(coins.findAllByIdIn(Set.of(btc.getId(), usdt.getId()))).thenReturn(List.of(usdt, btc));

        assertThat(api.pairCoins(pair.getId()))
                .contains(new PairCoins(
                        pair.getId(),
                        new CoinListing(btc.getId(), "BTC", btc.getCoinName()),
                        new CoinListing(usdt.getId(), "USDT", usdt.getCoinName())));
    }

    @Test
    void TR02_anUnknownPair_hasNoCoins() {
        UUID unknown = UUID.randomUUID();
        when(pairs.findById(unknown)).thenReturn(Optional.empty());

        assertThat(api.pairCoins(unknown)).isEmpty();
        verifyNoInteractions(coins);
    }

    /** fk_crypto_pair_base_coin keeps both coins stored, so a pair without one is a defect, not an empty answer. */
    @Test
    void TR02_aPairWhoseCoinIsNotStored_isADefect() {
        Coin usdt = Coin.fromExchange("USDT");
        CryptoPair pair = CryptoPair.register(UUID.randomUUID(), usdt.getId(), "BTCUSDT");
        when(pairs.findById(pair.getId())).thenReturn(Optional.of(pair));
        when(coins.findAllByIdIn(Set.of(pair.getBaseCoinId(), usdt.getId()))).thenReturn(List.of(usdt));

        assertThatIllegalStateException().isThrownBy(() -> api.pairCoins(pair.getId()));
    }

    @Test
    void noPairIds_needNoQuery() {
        assertThat(api.pairListings(List.of())).isEmpty();
        verifyNoInteractions(pairs);
    }

    @Test
    void BR33_aCurrentEntryWithoutTheValue_givesNothing() {
        when(prices.latest(MarketType.FUTURES, "BTCUSDT")).thenReturn(found(price(null, null)));

        assertThat(api.currentLastPrice(MarketType.FUTURES, "BTCUSDT")).isEmpty();
        assertThat(api.currentMarkPrice("BTCUSDT")).isEmpty();
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
