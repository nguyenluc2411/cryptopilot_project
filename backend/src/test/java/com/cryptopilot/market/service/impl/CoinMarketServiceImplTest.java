package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.client.CoinPaprikaGlobal;
import com.cryptopilot.market.client.FearGreedReading;
import com.cryptopilot.market.config.CoinListProperties;
import com.cryptopilot.market.dto.response.CoinMarketResponse;
import com.cryptopilot.market.dto.response.CoinMarketResponse.CoinRow;
import com.cryptopilot.market.entity.Coin;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.MarketReferenceStore;
import com.cryptopilot.market.model.MarketReferenceStore.Supply;
import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.CoinMarketRepository;
import com.cryptopilot.market.repository.CoinMarketRepository.OpenInterestPoint;
import com.cryptopilot.market.repository.CoinMarketRepository.Series;
import com.cryptopilot.market.repository.CoinRepository;
import com.cryptopilot.market.repository.CryptoPairRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The market list of coins: a coin is the base of a listed pair, its series picked by tier (Spot USDT, Futures USDT,
 * Spot, Futures); its figures come from batched reads of a fixed count; the list is rebuilt in the background, or by a
 * request once it is older than the interval when the job is off; a failed rebuild keeps the last one.
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
    private final CoinMarketServiceImpl service = service(true);

    private final Map<String, Coin> bySymbol = new HashMap<>();

    @BeforeEach
    void listCoins() {
        for (String symbol : List.of(
                "BTC", "ETH", "SOL", "XRP", "USDC", "USDT", "PEPE", "FDUSD", "WBTC", "PAXG", "EUR", "ADA", "DOT")) {
            bySymbol.put(symbol, Coin.fromExchange(symbol));
        }
        when(coins.findAllByIdIn(any())).thenReturn(List.copyOf(bySymbol.values()));
        when(figures.closesAt(anyCollection(), any(), any(), any())).thenReturn(Map.of());
        when(figures.closesBetween(anyCollection(), any(), any(), any())).thenReturn(Map.of());
        when(figures.openInterestHourly(any(), any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void aCoinIsTheBaseOfAListedPair_withItsSpotSeriesAndQuote_andAQuoteOnlyCoinIsLeftOut() {
        CryptoPair btcSpot = pair("BTC", "USDT");
        CryptoPair btcFdusd = pair("BTC", "FDUSD");
        CryptoPair btcFutures = pair("BTC", "USDT");
        CryptoPair pepeFutures = pair("PEPE", "USDT");
        when(pairs.findEnabledOnSpot()).thenReturn(List.of(btcFdusd, btcSpot));
        when(pairs.findEnabledOnFutures()).thenReturn(List.of(btcFutures, pepeFutures));
        Series btc = new Series(btcSpot.getId(), MarketType.SPOT);
        when(figures.closesAt(anyCollection(), eq(MarketInterval.FIFTEEN_MINUTES), any(), any()))
                .thenReturn(Map.of(btc, new BigDecimal("62000")));
        when(figures.closesAt(anyCollection(), eq(MarketInterval.ONE_HOUR), any(), any()))
                .thenReturn(Map.of(btc, new BigDecimal("70000")));
        when(figures.closesBetween(anyCollection(), eq(MarketInterval.ONE_HOUR), any(), any()))
                .thenReturn(Map.of(btc, List.of(new BigDecimal("70000.000000000000"), new BigDecimal("63000.5"))));
        reference.supplies(Map.of("BTC", new Supply(1, new BigDecimal("20000000"))), NOW);

        CoinMarketResponse answer = service.coins();

        assertThat(answer.coins()).extracting(CoinRow::symbol).containsExactly("BTC", "PEPE");
        CoinRow row = answer.coins().getFirst();
        assertThat(row.spotSymbol()).isEqualTo("BTCUSDT");
        assertThat(row.futuresSymbol()).isEqualTo("BTCUSDT");
        assertThat(row.quote()).isEqualTo("USDT");
        assertThat(row.rank()).isEqualTo(1);
        assertThat(row.circulatingSupply()).isEqualByComparingTo("20000000");
        assertThat(row.price1hAgo()).isEqualByComparingTo("62000");
        assertThat(row.price7dAgo()).isEqualByComparingTo("70000");
        // The small chart is sent without the trailing zeros of the column's scale.
        assertThat(row.sparkline7d()).extracting(BigDecimal::toPlainString).containsExactly("70000", "63000.5");
        CoinRow pepe = answer.coins().get(1);
        assertThat(pepe.spotSymbol()).isNull();
        assertThat(pepe.futuresSymbol()).isEqualTo("PEPEUSDT");
        assertThat(pepe.quote()).isEqualTo("USDT");
        assertThat(pepe.rank()).isNull();
    }

    @Test
    void theReadsAreAFixedNumberOfStatements_whateverTheNumberOfCoins() {
        when(pairs.findEnabledOnFutures()).thenReturn(List.of());
        List<CryptoPair> two = List.of(pair("BTC", "USDT"), pair("ETH", "USDT"));
        when(pairs.findEnabledOnSpot()).thenReturn(two);
        service.refresh();
        List<CryptoPair> six = List.of(
                pair("BTC", "USDT"),
                pair("ETH", "USDT"),
                pair("SOL", "USDT"),
                pair("XRP", "USDT"),
                pair("ADA", "USDT"),
                pair("DOT", "USDT"));
        when(pairs.findEnabledOnSpot()).thenReturn(six);
        service.refresh();

        // Per build: the hour-ago, week-ago and two daily closes, one read of the week, one of the open interest.
        verify(figures, times(2 * 4)).closesAt(anyCollection(), any(), any(), any());
        verify(figures, times(2)).closesBetween(anyCollection(), any(), any(), any());
        verify(figures, times(2)).openInterestHourly(any(), any(), any(), any());
        verify(coins, times(2)).findAllByIdIn(any());
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
        service.refresh();

        CoinMarketResponse after = service.coins();
        assertThat(after.global().marketCapUsd()).isEqualByComparingTo("2307025721623");
        assertThat(after.fearGreed().value()).isEqualTo(12);
    }

    @Test
    void requestsReadTheLastBuiltList_andOnlyTheFirstOneBuildsIt() {
        CryptoPair btc = pair("BTC", "USDT");
        when(pairs.findEnabledOnSpot()).thenReturn(List.of(btc));
        when(pairs.findEnabledOnFutures()).thenReturn(List.of());

        CoinMarketResponse first = service.coins();
        clock.advance(Duration.ofHours(1));
        assertThat(service.coins()).isSameAs(first);
        verify(pairs, times(1)).findEnabledOnSpot();

        service.refresh();
        assertThat(service.coins()).isNotSameAs(first);
        assertThat(service.coins().asOf()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void aFailedRebuild_keepsTheLastList_butAFailedFirstBuildIsTheCallersError() {
        CryptoPair btc = pair("BTC", "USDT");
        when(pairs.findEnabledOnFutures()).thenReturn(List.of());
        doThrow(new IllegalStateException("database down")).when(pairs).findEnabledOnSpot();

        assertThatThrownBy(service::coins).isInstanceOf(IllegalStateException.class);

        doReturn(List.of(btc)).when(pairs).findEnabledOnSpot();
        CoinMarketResponse built = service.coins();
        doThrow(new IllegalStateException("database down")).when(pairs).findEnabledOnSpot();
        service.refresh();

        assertThat(service.coins()).isSameAs(built);
    }

    @Test
    void theAltcoinSeason_isTheShareOfAltcoinsThatBeatBitcoin_peggedCoinsLeftOut() {
        List<String> symbols = List.of("BTC", "ETH", "SOL", "XRP", "USDC", "WBTC", "PAXG", "EUR");
        List<CryptoPair> listed = new ArrayList<>();
        for (String symbol : symbols) {
            listed.add(pair(symbol, "USDT"));
        }
        when(pairs.findEnabledOnSpot()).thenReturn(listed);
        when(pairs.findEnabledOnFutures()).thenReturn(List.of());
        // Over 90 days: BTC +10 %, ETH +20 %, SOL +5 %, XRP +30 %; the pegged coins would all beat BTC if counted.
        String[][] moves = {
            {"100", "110"},
            {"100", "120"},
            {"100", "105"},
            {"100", "130"},
            {"1", "2"},
            {"1", "2"},
            {"1", "2"},
            {"1", "2"}
        };
        Map<Series, BigDecimal> then = new HashMap<>();
        Map<Series, BigDecimal> latest = new HashMap<>();
        for (int i = 0; i < listed.size(); i++) {
            Series series = new Series(listed.get(i).getId(), MarketType.SPOT);
            then.put(series, new BigDecimal(moves[i][0]));
            latest.put(series, new BigDecimal(moves[i][1]));
        }
        when(figures.closesAt(anyCollection(), eq(MarketInterval.ONE_DAY), any(), any()))
                .thenAnswer(call -> {
                    Instant at = call.getArgument(2);
                    return at.isBefore(NOW.minus(Duration.ofDays(1))) ? then : latest;
                });

        CoinMarketResponse answer = service.coins();

        assertThat(answer.altcoinSeason().altcoins()).isEqualTo(3);
        assertThat(answer.altcoinSeason().index()).isEqualTo(67);
        assertThat(answer.altcoinSeason().days()).isEqualTo(90);
        assertThat(CoinMarketServiceImpl.PEGGED)
                .contains("EUR", "AEUR", "EURI", "XUSD", "WBTC", "WBETH", "PAXG", "USDT", "FDUSD");
    }

    @Test
    void theOpenInterest_isTheLatestSum_withItsChangeOverTheDay() {
        CryptoPair btc = pair("BTC", "USDT");
        when(pairs.findEnabledOnSpot()).thenReturn(List.of());
        when(pairs.findEnabledOnFutures()).thenReturn(List.of(btc));
        when(figures.openInterestHourly(any(), any(), any(), any()))
                .thenReturn(List.of(
                        new OpenInterestPoint(NOW.minus(Duration.ofHours(24)), new BigDecimal("1000"), 1),
                        new OpenInterestPoint(NOW, new BigDecimal("1100"), 1)));

        CoinMarketResponse answer = service.coins();

        assertThat(answer.openInterest().valueUsd()).isEqualByComparingTo("1100");
        assertThat(answer.openInterest().change24hPercent()).isEqualByComparingTo("10.00");
        assertThat(answer.openInterest().lastDay()).hasSize(2);
    }

    @Test
    void withTheJobOff_aRequestRebuildsAListOlderThanTheInterval_andNotBefore() {
        CoinMarketServiceImpl off = service(false);
        CryptoPair btc = pair("BTC", "USDT");
        when(pairs.findEnabledOnSpot()).thenReturn(List.of(btc));
        when(pairs.findEnabledOnFutures()).thenReturn(List.of());

        CoinMarketResponse first = off.coins();
        clock.advance(Duration.ofSeconds(60));
        assertThat(off.coins()).isSameAs(first);
        verify(pairs, times(1)).findEnabledOnSpot();

        clock.advance(Duration.ofSeconds(1));
        CoinMarketResponse second = off.coins();
        assertThat(second).isNotSameAs(first);
        assertThat(second.asOf()).isEqualTo(NOW.plusSeconds(61));
        assertThat(off.coins()).isSameAs(second);
        verify(pairs, times(2)).findEnabledOnSpot();
    }

    @Test
    void withTheJobOff_aFailedRebuildOnARequest_answersThePreviousList() {
        CoinMarketServiceImpl off = service(false);
        CryptoPair btc = pair("BTC", "USDT");
        when(pairs.findEnabledOnSpot()).thenReturn(List.of(btc));
        when(pairs.findEnabledOnFutures()).thenReturn(List.of());
        CoinMarketResponse built = off.coins();

        clock.advance(Duration.ofMinutes(5));
        doThrow(new IllegalStateException("database down")).when(pairs).findEnabledOnSpot();

        assertThat(off.coins()).isSameAs(built);
        // The failure is not remembered: the next request tries again.
        CryptoPair eth = pair("ETH", "USDT");
        doReturn(List.of(eth)).when(pairs).findEnabledOnSpot();
        assertThat(off.coins().coins()).extracting(CoinRow::symbol).containsExactly("ETH");
    }

    @Test
    void withTheJobOn_requestsNeverRebuild_howeverOldTheList() {
        CryptoPair btc = pair("BTC", "USDT");
        when(pairs.findEnabledOnSpot()).thenReturn(List.of(btc));
        when(pairs.findEnabledOnFutures()).thenReturn(List.of());

        CoinMarketResponse first = service.coins();
        clock.advance(Duration.ofDays(1));

        assertThat(service.coins()).isSameAs(first);
        verify(pairs, times(1)).findEnabledOnSpot();
    }

    @Test
    void theSeries_isSpotInUsdt_thenFuturesInUsdt_thenSpot_thenFutures_andTheSymbolsStay() {
        // BTC: Spot in FDUSD and USDT, Futures in USDT -> Spot USDT.
        CryptoPair btcSpotFdusd = pair("BTC", "FDUSD");
        CryptoPair btcSpotUsdt = pair("BTC", "USDT");
        CryptoPair btcFuturesUsdt = pair("BTC", "USDT");
        // ETH: Spot in FDUSD only, Futures in USDT -> Futures USDT.
        CryptoPair ethSpotFdusd = pair("ETH", "FDUSD");
        CryptoPair ethFuturesUsdt = pair("ETH", "USDT");
        // SOL: Spot in FDUSD then EUR, Futures in USDC -> the first Spot (FDUSD).
        CryptoPair solSpotFdusd = pair("SOL", "FDUSD");
        CryptoPair solSpotEur = pair("SOL", "EUR");
        CryptoPair solFuturesUsdc = pair("SOL", "USDC");
        // XRP: Futures in USDC only -> Futures USDC.
        CryptoPair xrpFuturesUsdc = pair("XRP", "USDC");
        when(pairs.findEnabledOnSpot())
                .thenReturn(List.of(btcSpotFdusd, btcSpotUsdt, ethSpotFdusd, solSpotFdusd, solSpotEur));
        when(pairs.findEnabledOnFutures())
                .thenReturn(List.of(btcFuturesUsdt, ethFuturesUsdt, solFuturesUsdc, xrpFuturesUsdc));
        Map<Series, List<BigDecimal>> week = Map.of(
                new Series(btcSpotUsdt.getId(), MarketType.SPOT), List.of(BigDecimal.ONE),
                new Series(ethFuturesUsdt.getId(), MarketType.FUTURES), List.of(BigDecimal.TWO),
                new Series(solSpotFdusd.getId(), MarketType.SPOT), List.of(BigDecimal.valueOf(3)),
                new Series(xrpFuturesUsdc.getId(), MarketType.FUTURES), List.of(BigDecimal.valueOf(4)));
        when(figures.closesBetween(anyCollection(), eq(MarketInterval.ONE_HOUR), any(), any()))
                .thenReturn(week);

        Map<String, CoinRow> rows = new HashMap<>();
        service.coins().coins().forEach(row -> rows.put(row.symbol(), row));

        assertThat(rows.get("BTC").quote()).isEqualTo("USDT");
        assertThat(rows.get("BTC").sparkline7d()).containsExactly(BigDecimal.ONE);
        assertThat(rows.get("BTC").spotSymbol()).isEqualTo("BTCUSDT");
        assertThat(rows.get("ETH").quote()).isEqualTo("USDT");
        assertThat(rows.get("ETH").sparkline7d()).containsExactly(BigDecimal.TWO);
        assertThat(rows.get("ETH").spotSymbol()).isEqualTo("ETHFDUSD");
        assertThat(rows.get("ETH").futuresSymbol()).isEqualTo("ETHUSDT");
        assertThat(rows.get("SOL").quote()).isEqualTo("FDUSD");
        assertThat(rows.get("SOL").sparkline7d()).containsExactly(BigDecimal.valueOf(3));
        assertThat(rows.get("SOL").futuresSymbol()).isEqualTo("SOLUSDC");
        assertThat(rows.get("XRP").quote()).isEqualTo("USDC");
        assertThat(rows.get("XRP").sparkline7d()).containsExactly(BigDecimal.valueOf(4));
        assertThat(rows.get("XRP").spotSymbol()).isNull();
    }

    private CoinMarketServiceImpl service(boolean jobEnabled) {
        return new CoinMarketServiceImpl(
                pairs, coins, figures, reference, new CoinListProperties(jobEnabled, Duration.ofSeconds(60)), clock);
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
}
