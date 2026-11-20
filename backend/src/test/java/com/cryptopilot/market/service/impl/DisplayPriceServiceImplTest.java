package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.fallback.OkxCandle;
import com.cryptopilot.market.client.fallback.OkxClient;
import com.cryptopilot.market.client.fallback.OkxException;
import com.cryptopilot.market.client.fallback.OkxProperties;
import com.cryptopilot.market.client.fallback.OkxTicker;
import com.cryptopilot.market.dto.response.FallbackPriceResponse;
import com.cryptopilot.market.dto.response.FallbackPricesResponse;
import com.cryptopilot.market.event.MarketFallbackUnavailable;
import com.cryptopilot.market.model.MarketIncident;
import com.cryptopilot.market.model.StreamTarget;
import com.cryptopilot.market.model.enums.DisplaySource;
import com.cryptopilot.market.model.enums.IncidentKind;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketHealthService;
import com.cryptopilot.market.service.StreamCandleService;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The display-only fallback prices of a lost market (D-158): matched to the streamed pairs by symbol or override,
 * flagged when far from the exchange's last price, shown only while the market is paused or recovering, and a failed
 * read told to the administrators once per incident (E9).
 *
 * <p>Rule: BR-09; D-158.
 */
class DisplayPriceServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-19T08:00:00Z");
    private static final UUID BTC = UUID.randomUUID();
    private static final UUID PEPE = UUID.randomUUID();
    private static final UUID DOGE = UUID.randomUUID();
    private static final UUID INCIDENT = UUID.randomUUID();

    private final OkxClient okx = mock(OkxClient.class);
    private final StreamCandleService streamed = mock(StreamCandleService.class);
    private final LatestMarketData latest = new LatestMarketData();
    private final MarketHealthService health = mock(MarketHealthService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private DisplayPriceServiceImpl service;

    @BeforeEach
    void service() {
        @SuppressWarnings("unchecked")
        ObjectProvider<OkxClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(okx);
        service = service(provider);
        when(streamed.targets(MarketType.FUTURES))
                .thenReturn(List.of(
                        new StreamTarget(BTC, "BTCUSDT"),
                        new StreamTarget(PEPE, "1000PEPEUSDT"),
                        new StreamTarget(DOGE, "DOGEUSDT")));
        when(streamed.targets(MarketType.SPOT)).thenReturn(List.of(new StreamTarget(BTC, "BTCUSDT")));
        when(okx.swapTickers())
                .thenReturn(List.of(
                        ticker("BTC-USDT-SWAP", "70000", "68000"),
                        ticker("PEPE-USDT-SWAP", "0.0102", null),
                        ticker("ETH-USDT-SWAP", "2000", "2000")));
        when(okx.spotTickers()).thenReturn(List.of(ticker("BTC-USDT", "65100", "65000")));
        when(health.dataLost(any())).thenReturn(true);
        when(health.openIncident(any()))
                .thenReturn(Optional.of(new MarketIncident(
                        INCIDENT,
                        MarketType.FUTURES,
                        IncidentKind.OUTAGE,
                        IncidentReason.STREAM_LOST,
                        NOW.minusSeconds(60),
                        NOW.minusSeconds(30),
                        null,
                        null,
                        null,
                        null)));
    }

    /**
     * A pair matches the instrument its symbol names, or its override; one the fallback does not list has no price.
     * A price more than 5 % from the exchange's mark is flagged.
     */
    @Test
    void D158_thePairsMatchTheirInstruments_andAFarPriceIsFlagged() {
        latest.recordMarkPrice(BTC, mark("BTCUSDT", "65000"));
        latest.recordMarkPrice(PEPE, mark("1000PEPEUSDT", "0.0101"));

        service.refresh(MarketType.FUTURES);
        FallbackPricesResponse futures = service.prices(MarketType.FUTURES);

        assertThat(futures.source()).isEqualTo(DisplaySource.OKX);
        assertThat(futures.asOf()).isEqualTo(NOW);
        assertThat(futures.prices())
                .extracting(FallbackPriceResponse::symbol)
                .containsExactly("1000PEPEUSDT", "BTCUSDT");
        FallbackPriceResponse btc = futures.prices().get(1);
        assertThat(btc.lastPrice()).isEqualByComparingTo("70000");
        assertThat(btc.changePercent24h()).isEqualByComparingTo("2.94");
        assertThat(btc.diverged()).as("7.7 % from the mark").isTrue();
        assertThat(futures.prices().getFirst().diverged()).isFalse();
        assertThat(futures.prices().getFirst().changePercent24h()).isNull();

        service.refresh(MarketType.SPOT);
        assertThat(service.prices(MarketType.SPOT).prices())
                .singleElement()
                .satisfies(spot ->
                        assertThat(spot.diverged()).as("no exchange price").isFalse());
    }

    /**
     * The fallback is shown only while the market's data is lost (paused, or recovering from an incident that lost
     * data); live, under maintenance or recovering from a maintenance, the exchange's.
     */
    @Test
    void D158_theFallback_isShownOnlyWhileTheMarketIsLost() {
        service.refresh(MarketType.FUTURES);
        assertThat(service.source(MarketType.FUTURES)).isEqualTo(DisplaySource.OKX);

        when(health.dataLost(MarketType.FUTURES)).thenReturn(false);
        assertThat(service.source(MarketType.FUTURES)).isEqualTo(DisplaySource.BINANCE);
        assertThat(service.prices(MarketType.FUTURES).prices()).isEmpty();
        assertThat(service.candles(MarketType.FUTURES, "BTCUSDT", 10)).isEmpty();

        when(health.dataLost(MarketType.FUTURES)).thenReturn(true);
        service.clear(MarketType.FUTURES);
        assertThat(service.source(MarketType.FUTURES)).as("nothing read").isEqualTo(DisplaySource.NONE);
    }

    /** E9: a failed read leaves no price, and the administrators hear of it once per incident. */
    @Test
    void D158_aFailedRead_leavesNoPrice_andIsToldOncePerIncident() {
        service.refresh(MarketType.FUTURES);
        when(okx.swapTickers()).thenThrow(new OkxException("OKX answered 503 for tickers", 503, null));

        service.refresh(MarketType.FUTURES);
        service.refresh(MarketType.FUTURES);

        assertThat(service.source(MarketType.FUTURES)).isEqualTo(DisplaySource.NONE);
        verify(events, times(1)).publishEvent(any(MarketFallbackUnavailable.class));

        when(health.openIncident(MarketType.SPOT)).thenReturn(Optional.empty());
        when(okx.spotTickers()).thenThrow(new OkxException("down", 0, null));
        service.refresh(MarketType.SPOT);
        verify(events, times(1)).publishEvent(any(MarketFallbackUnavailable.class));
    }

    /** Without the client, as with the fallback off, a lost market shows no price and nothing is called. */
    @Test
    void D158_withTheFallbackOff_aLostMarketShowsNoPrice() {
        @SuppressWarnings("unchecked")
        ObjectProvider<OkxClient> none = mock(ObjectProvider.class);
        DisplayPriceServiceImpl off = service(none);

        off.refresh(MarketType.SPOT);

        assertThat(off.source(MarketType.SPOT)).isEqualTo(DisplaySource.NONE);
        assertThat(off.candles(MarketType.SPOT, "BTCUSDT", 10)).isEmpty();
        verify(events, never()).publishEvent(any());
    }

    /** The candles of a listed pair come oldest first while the market is lost; a pair not listed has none. */
    @Test
    void D158_theCandlesOfAListedPair_areRead_whileTheMarketIsLost() {
        OkxCandle candle = new OkxCandle(
                NOW, BigDecimal.ONE, BigDecimal.TWO, BigDecimal.ONE, BigDecimal.TWO, BigDecimal.TEN, true);
        when(okx.minuteCandles("BTC-USDT-SWAP", 5)).thenReturn(List.of(candle));
        service.refresh(MarketType.FUTURES);

        assertThat(service.candles(MarketType.FUTURES, "btcusdt", 5))
                .singleElement()
                .satisfies(read -> assertThat(read.close()).isEqualByComparingTo("2"));
        assertThat(service.candles(MarketType.FUTURES, "DOGEUSDT", 5)).isEmpty();
        assertThatThrownBy(() -> service.candles(MarketType.FUTURES, "BTCUSDT", 0))
                .isInstanceOf(FieldValidationException.class);

        when(okx.minuteCandles("BTC-USDT-SWAP", 5)).thenThrow(new OkxException("down", 0, null));
        assertThat(service.candles(MarketType.FUTURES, "BTCUSDT", 5)).isEmpty();
    }

    @Test
    void D158_aSymbol_isSplitBeforeItsQuoteCoin() {
        assertThat(service.instrumentOf(MarketType.SPOT, "ETHBTC")).isEqualTo("ETH-BTC");
        assertThat(service.instrumentOf(MarketType.SPOT, "BTCFDUSD")).isEqualTo("BTC-FDUSD");
        assertThat(service.instrumentOf(MarketType.FUTURES, "SOLUSDC")).isEqualTo("SOL-USDC-SWAP");
        assertThat(service.instrumentOf(MarketType.SPOT, "USDT")).isEqualTo("USDT");
    }

    private DisplayPriceServiceImpl service(ObjectProvider<OkxClient> provider) {
        return new DisplayPriceServiceImpl(
                provider,
                new OkxProperties(
                        true,
                        URI.create("https://www.okx.com"),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(3),
                        new BigDecimal("0.05"),
                        Map.of("FUTURES.1000PEPEUSDT", "PEPE-USDT-SWAP")),
                streamed,
                latest,
                health,
                events,
                mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OkxTicker ticker(String instrument, String last, String open) {
        return new OkxTicker(
                instrument,
                new BigDecimal(last),
                open == null ? null : new BigDecimal(open),
                null,
                null,
                null,
                NOW.minusSeconds(1));
    }

    private static MarkPriceMessage mark(String symbol, String price) {
        BigDecimal value = new BigDecimal(price);
        return new MarkPriceMessage(symbol, value, value, BigDecimal.ZERO, NOW.plusSeconds(3600), NOW);
    }
}
