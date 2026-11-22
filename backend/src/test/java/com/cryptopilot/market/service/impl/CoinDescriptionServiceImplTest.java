package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.client.CoinPaprikaClient;
import com.cryptopilot.market.client.CoinPaprikaCoin;
import com.cryptopilot.market.client.CoinPaprikaException;
import com.cryptopilot.market.client.CoinPaprikaProperties;
import com.cryptopilot.market.entity.Coin;
import com.cryptopilot.market.repository.CoinRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Matching by ticker, best rank first, or by the configured id; one logo call per coin still without a logo; a failure
 * that writes nothing or keeps what was read; a coin with nothing more to add is tried again only after a day.
 *
 * <p>Rule: SRS 3.1.5.
 */
class CoinDescriptionServiceImplTest {

    private final CoinRepository coins = mock(CoinRepository.class);
    private final CoinPaprikaClient client = mock(CoinPaprikaClient.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-17T06:00:00Z"));

    private CoinDescriptionServiceImpl service(Map<String, String> overrides) {
        return new CoinDescriptionServiceImpl(
                coins,
                client,
                new CoinPaprikaProperties(
                        true,
                        URI.create("https://api.coinpaprika.com/v1"),
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofHours(24),
                        overrides),
                mock(PlatformTransactionManager.class),
                clock);
    }

    private final CoinDescriptionServiceImpl service = service(Map.of());

    @Test
    void nothingToDescribeCallsNobody() {
        when(coins.findUndescribed()).thenReturn(List.of());

        assertThat(service.describeCoins()).isZero();

        verify(client, never()).coins();
    }

    @Test
    void theBestRankedTrackedCoinWithTheTickerWins() {
        Coin btc = Coin.fromExchange("BTC");
        Coin usdt = Coin.fromExchange("USDT");
        undescribed(btc, usdt);
        listed(
                paprika("btc-big-copy", "BTC", "Big Copy", 5989, true),
                paprika("btc-bitcoin", "BTC", "Bitcoin", 1, true),
                paprika("btc-dead", "BTC", "Dead", 0, false),
                paprika("usdt-tether", "USDT", "Tether", 3, true));

        assertThat(service.describeCoins()).isEqualTo(2);

        assertThat(btc.getCoinName()).isEqualTo("Bitcoin");
        assertThat(btc.getLogoUrl()).isEqualTo(logoOf("btc-bitcoin"));
        assertThat(usdt.getCoinName()).isEqualTo("Tether");
        verify(coins).save(btc);
        verify(coins).save(usdt);
    }

    @Test
    void anOverrideNamesTheCoinTheTickerWouldMiss() {
        Coin ton = Coin.fromExchange("TON");
        undescribed(ton);
        listed(
                paprika("ton-tontoken", "TON", "TonToken", 142, true),
                paprika("toncoin-the-open-network", "GRAM", "Toncoin", 38, true));

        assertThat(service(Map.of("ton", "toncoin-the-open-network")).describeCoins())
                .isEqualTo(1);

        assertThat(ton.getCoinName()).isEqualTo("Toncoin");
        assertThat(ton.getLogoUrl()).isEqualTo(logoOf("toncoin-the-open-network"));
    }

    /**
     * The logo calls stop as soon as the client's shared budget is spent (a rate-limited failure, nothing sent); the
     * names read so far are kept and the coins left without a logo get it from a later run.
     */
    @Test
    void theLogoCalls_stopOnTheClientsRateLimit_andALaterRunGoesOn() {
        Coin a = Coin.fromExchange("AAA");
        Coin b = Coin.fromExchange("BBB");
        Coin c = Coin.fromExchange("CCC");
        undescribed(a, b, c);
        listed(
                paprika("aaa-coin", "AAA", "Coin A", 1, true),
                paprika("bbb-coin", "BBB", "Coin B", 2, true),
                paprika("ccc-coin", "CCC", "Coin C", 3, true));
        when(client.logo("bbb-coin")).thenThrow(new CoinPaprikaException("budget spent", 429, null));

        service.describeCoins();

        assertThat(a.getLogoUrl()).isEqualTo(logoOf("aaa-coin"));
        assertThat(b.getLogoUrl()).isNull();
        assertThat(c.getLogoUrl()).isNull();
        assertThat(c.getCoinName()).as("names are still written").isEqualTo("Coin C");
        verify(client, never()).logo("ccc-coin");

        org.mockito.Mockito.reset(client);
        listed(
                paprika("aaa-coin", "AAA", "Coin A", 1, true),
                paprika("bbb-coin", "BBB", "Coin B", 2, true),
                paprika("ccc-coin", "CCC", "Coin C", 3, true));
        service.describeCoins();

        assertThat(b.getLogoUrl()).isEqualTo(logoOf("bbb-coin"));
        assertThat(c.getLogoUrl()).isEqualTo(logoOf("ccc-coin"));
    }

    @Test
    void aContractInThousandsTakesTheLogoOfItsCoinButKeepsItsNameAndTheLogoIsReadOnce() {
        Coin pepe = Coin.fromExchange("PEPE");
        Coin thousand = Coin.fromExchange("1000PEPE");
        undescribed(pepe, thousand);
        listed(paprika("pepe-pepe", "PEPE", "Pepe", 50, true));

        assertThat(service.describeCoins()).isEqualTo(2);

        assertThat(thousand.getCoinName()).isEqualTo("1000PEPE");
        assertThat(thousand.getLogoUrl()).isEqualTo(logoOf("pepe-pepe"));
        verify(client, times(1)).logo("pepe-pepe");
    }

    @Test
    void aCoinThatHasALogoAlreadyCostsNoLogoCall() {
        Coin btc = Coin.fromExchange("BTC");
        btc.describe(null, "https://own.example/btc.png");
        undescribed(btc);
        listed(paprika("btc-bitcoin", "BTC", "Bitcoin", 1, true));

        assertThat(service.describeCoins()).isEqualTo(1);

        assertThat(btc.getCoinName()).isEqualTo("Bitcoin");
        assertThat(btc.getLogoUrl()).isEqualTo("https://own.example/btc.png");
        verify(client, never()).logo(anyString());
    }

    @Test
    void aCoinNotFoundStaysAsItIs() {
        Coin rare = Coin.fromExchange("RARE");
        undescribed(rare);
        listed(paprika("btc-bitcoin", "BTC", "Bitcoin", 1, true));

        assertThat(service.describeCoins()).isZero();

        verify(coins, never()).save(any());
        verify(client, never()).logo(anyString());
    }

    @Test
    void aCoinTriedWithNothingToAdd_isNotReadAgainInThisProcess() {
        Coin rare = Coin.fromExchange("RARE");
        Coin thousand = Coin.fromExchange("1000PEPE");
        undescribed(rare, thousand);
        listed(paprika("pepe-pepe", "PEPE", "Pepe", 50, true));

        service.describeCoins();
        // RARE has no match and 1000PEPE has its logo but keeps its own name: nothing more to gain from the list.
        assertThat(service.describeCoins()).isZero();

        verify(client, times(1)).coins();
    }

    @Test
    void aSettledCoin_isTriedAgainAfterTheRetryDelay_andNotBefore() {
        Coin rare = Coin.fromExchange("RARE");
        undescribed(rare);
        listed(paprika("pepe-pepe", "PEPE", "Pepe", 50, true));

        service.describeCoins();
        clock.advance(Duration.ofHours(24));
        service.describeCoins();
        verify(client, times(1)).coins();

        clock.advance(Duration.ofSeconds(1));
        listed(paprika("rare-rare", "RARE", "Rare", 900, true));
        assertThat(service.describeCoins()).isEqualTo(1);

        verify(client, times(2)).coins();
        assertThat(rare.getCoinName()).isEqualTo("Rare");
    }

    @Test
    void aCoinWhoseLogoCallsStopped_isReadAgainNextTime() {
        Coin btc = Coin.fromExchange("BTC");
        undescribed(btc);
        when(client.coins()).thenReturn(List.of(paprika("btc-bitcoin", "BTC", "Bitcoin", 1, true)));
        when(client.logo("btc-bitcoin")).thenThrow(new CoinPaprikaException("slow down", 429, null));

        service.describeCoins();
        service.describeCoins();

        verify(client, times(2)).coins();
    }

    @Test
    void aFailedListWritesNothingAndIsThrown() {
        undescribed(Coin.fromExchange("BTC"));
        when(client.coins()).thenThrow(new CoinPaprikaException("down", 503, null));

        assertThatThrownBy(service::describeCoins).isInstanceOf(CoinPaprikaException.class);

        verify(coins, never()).save(any());
    }

    @Test
    void aRefusedLogoSkipsThatCoinButARateLimitStopsTheLogoCallsAndKeepsTheNames() {
        Coin btc = Coin.fromExchange("BTC");
        Coin eth = Coin.fromExchange("ETH");
        Coin sol = Coin.fromExchange("SOL");
        undescribed(btc, eth, sol);
        when(client.coins())
                .thenReturn(List.of(
                        paprika("btc-bitcoin", "BTC", "Bitcoin", 1, true),
                        paprika("eth-ethereum", "ETH", "Ethereum", 2, true),
                        paprika("sol-solana", "SOL", "Solana", 7, true)));
        when(client.logo("btc-bitcoin")).thenThrow(new CoinPaprikaException("no such coin", 404, null));
        when(client.logo("eth-ethereum")).thenThrow(new CoinPaprikaException("slow down", 429, null));

        assertThat(service.describeCoins()).isEqualTo(3);

        assertThat(btc.getCoinName()).isEqualTo("Bitcoin");
        assertThat(btc.getLogoUrl()).isNull();
        assertThat(eth.getCoinName()).isEqualTo("Ethereum");
        assertThat(sol.getCoinName()).isEqualTo("Solana");
        assertThat(sol.getLogoUrl()).isNull();
        verify(client, never()).logo("sol-solana");
    }

    @Test
    void underlyingStripsOnlyTheMultiplierPrefixes() {
        assertThat(CoinDescriptionServiceImpl.underlying("1000PEPE")).contains("PEPE");
        assertThat(CoinDescriptionServiceImpl.underlying("1000000MOG")).contains("MOG");
        assertThat(CoinDescriptionServiceImpl.underlying("1MBABYDOGE")).contains("BABYDOGE");
        assertThat(CoinDescriptionServiceImpl.underlying("1INCH")).isEmpty();
        assertThat(CoinDescriptionServiceImpl.underlying("BTC")).isEmpty();
    }

    /** A clock a test moves. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private void undescribed(Coin... list) {
        when(coins.findUndescribed()).thenReturn(List.of(list));
        when(coins.findAllByIdIn(any())).thenReturn(List.of(list));
    }

    private void listed(CoinPaprikaCoin... list) {
        when(client.coins()).thenReturn(List.of(list));
        for (CoinPaprikaCoin coin : list) {
            when(client.logo(coin.id())).thenReturn(Optional.of(logoOf(coin.id())));
        }
    }

    private static CoinPaprikaCoin paprika(String id, String symbol, String name, int rank, boolean active) {
        return new CoinPaprikaCoin(id, symbol, name, rank, active);
    }

    private static String logoOf(String id) {
        return "https://static.coinpaprika.com/coin/" + id + "/logo.png";
    }
}
