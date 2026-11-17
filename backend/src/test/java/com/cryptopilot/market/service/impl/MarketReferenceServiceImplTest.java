package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.client.CoinPaprikaClient;
import com.cryptopilot.market.client.CoinPaprikaException;
import com.cryptopilot.market.client.CoinPaprikaGlobal;
import com.cryptopilot.market.client.CoinPaprikaProperties;
import com.cryptopilot.market.client.CoinPaprikaTicker;
import com.cryptopilot.market.entity.Coin;
import com.cryptopilot.market.model.MarketReferenceStore;
import com.cryptopilot.market.repository.CoinRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The cap on every refresh, the supplies hourly and matched like the names and logos (best rank, or the configured
 * id), and a failed read that keeps the last good figures.
 *
 * <p>Rule: SRS 3.3.1.
 */
class MarketReferenceServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-17T06:00:00Z");

    private final CoinPaprikaClient client = mock(CoinPaprikaClient.class);
    private final CoinRepository coins = mock(CoinRepository.class);
    private final MarketReferenceStore store = new MarketReferenceStore();

    private MarketReferenceServiceImpl service(Instant now) {
        return new MarketReferenceServiceImpl(
                client,
                new CoinPaprikaProperties(
                        true,
                        URI.create("https://api.coinpaprika.com/v1"),
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Map.of("TON", "toncoin-the-open-network")),
                coins,
                store,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void readsTheCap_andTheSuppliesOfTheListedCoins_byBestRankOrOverride() {
        when(client.global()).thenReturn(global("2300000000000"));
        when(client.tickers())
                .thenReturn(List.of(
                        new CoinPaprikaTicker("btc-copy", "BTC", 5000, new BigDecimal("1")),
                        new CoinPaprikaTicker("btc-bitcoin", "BTC", 1, new BigDecimal("20000000")),
                        new CoinPaprikaTicker("ton-tontoken", "TON", 142, new BigDecimal("5")),
                        new CoinPaprikaTicker("toncoin-the-open-network", "GRAM", 38, new BigDecimal("2500000000")),
                        new CoinPaprikaTicker("no-supply", "NS", 9, null)));
        when(coins.findListed())
                .thenReturn(List.of(Coin.fromExchange("BTC"), Coin.fromExchange("TON"), Coin.fromExchange("NS")));

        service(NOW).refresh();

        assertThat(store.global().orElseThrow().marketCapUsd()).isEqualByComparingTo("2300000000000");
        Map<String, MarketReferenceStore.Supply> supplies =
                store.supplies().orElseThrow().bySymbol();
        assertThat(supplies).containsOnlyKeys("BTC", "TON");
        assertThat(supplies.get("BTC").rank()).isEqualTo(1);
        assertThat(supplies.get("TON").circulatingSupply()).isEqualByComparingTo("2500000000");
    }

    @Test
    void readsTheSuppliesHourly_andKeepsTheLastGoodFiguresOnAFailure() {
        when(client.global()).thenReturn(global("1"));
        when(client.tickers()).thenReturn(List.of(new CoinPaprikaTicker("btc-bitcoin", "BTC", 1, BigDecimal.TEN)));
        when(coins.findListed()).thenReturn(List.of(Coin.fromExchange("BTC")));
        service(NOW).refresh();

        when(client.global()).thenThrow(new CoinPaprikaException("down", 503, null));
        service(NOW.plus(Duration.ofMinutes(10))).refresh();
        verify(client, times(1)).tickers();
        assertThat(store.global().orElseThrow().marketCapUsd()).isEqualByComparingTo("1");

        when(client.tickers()).thenThrow(new CoinPaprikaException("slow down", 429, null));
        service(NOW.plus(Duration.ofHours(1))).refresh();
        verify(client, times(2)).tickers();
        assertThat(store.supplies().orElseThrow().readAt()).isEqualTo(NOW);
    }

    private static CoinPaprikaGlobal global(String cap) {
        return new CoinPaprikaGlobal(new BigDecimal(cap), null, null, null, NOW);
    }
}
