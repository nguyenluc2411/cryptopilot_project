package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.client.CoinPaprikaGlobal;
import com.cryptopilot.market.client.FearGreedReading;
import com.cryptopilot.market.dto.response.CoinMarketResponse;
import com.cryptopilot.market.dto.response.CoinMarketResponse.CoinRow;
import com.cryptopilot.market.entity.Coin;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.MarketReferenceStore;
import com.cryptopilot.market.model.MarketReferenceStore.Supply;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.CoinMarketRepository;
import com.cryptopilot.market.repository.CoinMarketRepository.OpenInterestPoint;
import com.cryptopilot.market.repository.CoinRepository;
import com.cryptopilot.market.repository.CryptoPairRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The market list of coins: a coin is the base of a listed pair, its series the Spot pair's, its figures from the
 * stored candles and the reference store; the altcoin season and the open interest; one answer served for a minute.
 *
 * <p>Rule: SRS 3.3.1.
 */
class CoinMarketServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-17T06:00:00Z");

    private final CryptoPairRepository pairs = mock(CryptoPairRepository.class);
    private final CoinRepository coins = mock(CoinRepository.class);
    private final CoinMarketRepository figures = mock(CoinMarketRepository.class);
    private final MarketReferenceStore reference = new MarketReferenceStore();
    private final MutableClock clock = new MutableClock(NOW);
    private final CoinMarketServiceImpl service = new CoinMarketServiceImpl(pairs, coins, figures, reference, clock);

    private final Map<String, Coin> bySymbol = new HashMap<>();

    @BeforeEach
    void listCoins() {
        for (String symbol : List.of("BTC", "ETH", "SOL", "XRP", "USDC", "USDT", "PEPE")) {
            bySymbol.put(symbol, Coin.fromExchange(symbol));
        }
        when(coins.findAllByIdIn(any())).thenReturn(List.copyOf(bySymbol.values()));
        when(figures.closeAt(any(), any(), anyString(), any(), any())).thenReturn(Optional.empty());
        when(figures.closesSince(any(), any(), anyString(), any())).thenReturn(List.of());
        when(figures.openInterestHourly(any(), any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void aCoinIsTheBaseOfAListedPair_withItsSpotSeries_andAQuoteOnlyCoinIsLeftOut() {
        CryptoPair btcSpot = pair("BTC", "USDT");
        CryptoPair btcFutures = pair("BTC", "USDT");
        CryptoPair pepeFutures = pair("PEPE", "USDT");
        when(pairs.findEnabledOnSpot()).thenReturn(List.of(btcSpot));
        when(pairs.findEnabledOnFutures()).thenReturn(List.of(btcFutures, pepeFutures));
        when(figures.closeAt(eq(btcSpot.getId()), eq(MarketType.SPOT), eq("15m"), any(), any()))
                .thenReturn(Optional.of(new BigDecimal("62000")));
        when(figures.closeAt(eq(btcSpot.getId()), eq(MarketType.SPOT), eq("1h"), any(), any()))
                .thenReturn(Optional.of(new BigDecimal("70000")));
        when(figures.closesSince(eq(btcSpot.getId()), eq(MarketType.SPOT), eq("1h"), any()))
                .thenReturn(List.of(new BigDecimal("70000"), new BigDecimal("63000")));
        reference.supplies(Map.of("BTC", new Supply(1, new BigDecimal("20000000"))), NOW);

        CoinMarketResponse answer = service.coins();

        assertThat(answer.coins()).extracting(CoinRow::symbol).containsExactly("BTC", "PEPE");
        CoinRow btc = answer.coins().getFirst();
        assertThat(btc.spotSymbol()).isEqualTo("BTCUSDT");
        assertThat(btc.futuresSymbol()).isEqualTo("BTCUSDT");
        assertThat(btc.rank()).isEqualTo(1);
        assertThat(btc.circulatingSupply()).isEqualByComparingTo("20000000");
        assertThat(btc.price1hAgo()).isEqualByComparingTo("62000");
        assertThat(btc.price7dAgo()).isEqualByComparingTo("70000");
        assertThat(btc.sparkline7d()).hasSize(2);
        CoinRow pepe = answer.coins().get(1);
        assertThat(pepe.spotSymbol()).isNull();
        assertThat(pepe.futuresSymbol()).isEqualTo("PEPEUSDT");
        assertThat(pepe.rank()).isNull();
    }

    @Test
    void theReferenceFigures_comeFromTheStore_andAreAbsentUntilRead() {
        CryptoPair btc = pair("BTC", "USDT");
        when(pairs.findEnabledOnSpot()).thenReturn(List.of(btc));
        when(pairs.findEnabledOnFutures()).thenReturn(List.of());

        CoinMarketResponse before = service.coins();
        assertThat(before.global()).isNull();
        assertThat(before.fearGreed()).isNull();
        assertThat(before.altcoinSeason()).isNull();
        assertThat(before.openInterest()).isNull();

        reference.global(new CoinPaprikaGlobal(
                new BigDecimal("2307025721623"), new BigDecimal("0.61"), null, new BigDecimal("56.57"), NOW));
        reference.fearGreed(new FearGreedReading(12, "Extreme Fear", NOW));
        clock.advance(CoinMarketServiceImpl.TTL);

        CoinMarketResponse after = service.coins();
        assertThat(after.global().marketCapUsd()).isEqualByComparingTo("2307025721623");
        assertThat(after.fearGreed().value()).isEqualTo(12);
    }

    @Test
    void theAltcoinSeason_isTheShareOfAltcoinsThatBeatBitcoin_stablecoinsLeftOut() {
        CryptoPair btc = pair("BTC", "USDT");
        CryptoPair eth = pair("ETH", "USDT");
        CryptoPair sol = pair("SOL", "USDT");
        CryptoPair xrp = pair("XRP", "USDT");
        CryptoPair usdc = pair("USDC", "USDT");
        when(pairs.findEnabledOnSpot()).thenReturn(List.of(btc, eth, sol, xrp, usdc));
        when(pairs.findEnabledOnFutures()).thenReturn(List.of());
        // Over 90 days: BTC +10 %, ETH +20 %, SOL +5 %, XRP +30 %, USDC flat.
        daily(btc, "100", "110");
        daily(eth, "100", "120");
        daily(sol, "100", "105");
        daily(xrp, "100", "130");
        daily(usdc, "1", "1");

        CoinMarketResponse answer = service.coins();

        assertThat(answer.altcoinSeason().altcoins()).isEqualTo(3);
        assertThat(answer.altcoinSeason().index()).isEqualTo(67);
        assertThat(answer.altcoinSeason().days()).isEqualTo(90);
    }

    @Test
    void theOpenInterest_isTheLatestSum_withItsChangeOverTheDay() {
        CryptoPair btc = pair("BTC", "USDT");
        when(pairs.findEnabledOnSpot()).thenReturn(List.of());
        when(pairs.findEnabledOnFutures()).thenReturn(List.of(btc));
        when(figures.openInterestHourly(any(), any(), any(), any()))
                .thenReturn(List.of(
                        new OpenInterestPoint(NOW.minusSeconds(86_400), new BigDecimal("1000"), 1),
                        new OpenInterestPoint(NOW, new BigDecimal("1100"), 1)));

        CoinMarketResponse answer = service.coins();

        assertThat(answer.openInterest().valueUsd()).isEqualByComparingTo("1100");
        assertThat(answer.openInterest().change24hPercent()).isEqualByComparingTo("10.00");
        assertThat(answer.openInterest().lastDay()).hasSize(2);
    }

    @Test
    void oneAnswerIsServedForAMinute() {
        CryptoPair btc = pair("BTC", "USDT");
        when(pairs.findEnabledOnSpot()).thenReturn(List.of(btc));
        when(pairs.findEnabledOnFutures()).thenReturn(List.of());

        CoinMarketResponse first = service.coins();
        clock.advance(CoinMarketServiceImpl.TTL.minusSeconds(1));
        assertThat(service.coins()).isSameAs(first);
        clock.advance(java.time.Duration.ofSeconds(1));
        assertThat(service.coins()).isNotSameAs(first);
        verify(pairs, times(2)).findEnabledOnSpot();
    }

    private void daily(CryptoPair pair, String then, String latest) {
        when(figures.closeAt(eq(pair.getId()), eq(MarketType.SPOT), eq("1d"), any(), any()))
                .thenAnswer(call -> {
                    Instant at = call.getArgument(3);
                    return Optional.of(new BigDecimal(at.isBefore(NOW.minusSeconds(86_400)) ? then : latest));
                });
    }

    private CryptoPair pair(String base, String quote) {
        CryptoPair pair = mock(CryptoPair.class);
        UUID id = UUID.randomUUID();
        when(pair.getId()).thenReturn(id);
        when(pair.getSymbol()).thenReturn(base + quote);
        when(pair.getBaseCoinId()).thenReturn(bySymbol.get(base).getId());
        when(pair.getQuoteCoinId()).thenReturn(bySymbol.get(quote).getId());
        return pair;
    }

    /** A clock a test moves. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(java.time.Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
