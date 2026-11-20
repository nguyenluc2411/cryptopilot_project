package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.client.fallback.OkxClient;
import com.cryptopilot.market.client.fallback.OkxProperties;
import com.cryptopilot.market.client.fallback.OkxTicker;
import com.cryptopilot.market.dto.response.FallbackPricesResponse;
import com.cryptopilot.market.model.enums.DisplaySource;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketHealthService;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.market.service.StreamCandleService;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * M5, the isolation of the fallback prices (D-158, invariant I1): while a market is paused, a fallback price far from
 * the exchange's is shown, and the price the other modules fill, trigger and liquidate on stays the exchange's last
 * one; nothing of the fallback is stored.
 *
 * <p>Rule: BR-09; D-158.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class DisplayPriceIsolationTest {

    @Autowired
    private JdbcClient sql;

    @Autowired
    private MarketApi market;

    @Autowired
    private PriceCacheService prices;

    @Autowired
    private StreamCandleService streamed;

    @Autowired
    private LatestMarketData latest;

    @Autowired
    private PlatformTransactionManager transactions;

    private UUID pair;
    private String symbol;

    @BeforeEach
    void pair() {
        symbol = "FB" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, false, "TRADING", null, 0);
    }

    @AfterEach
    void removePair() {
        new MarketTestData(sql, Instant.now()).remove(pair);
    }

    @Test
    void M5_aFarFallbackPrice_isShown_andNeverBecomesTheExchangePrice() {
        prices.record(
                MarketType.SPOT,
                new TickerMessage(
                        symbol,
                        new BigDecimal("100"),
                        new BigDecimal("99.9"),
                        new BigDecimal("100.1"),
                        new BigDecimal("101"),
                        new BigDecimal("99"),
                        BigDecimal.ZERO,
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        Instant.now()));
        prices.flush();
        long candlesBefore = storedCandles();
        OkxClient okx = mock(OkxClient.class);
        String base = symbol.substring(0, symbol.length() - "USDT".length());
        when(okx.spotTickers())
                .thenReturn(List.of(
                        new OkxTicker(base + "-USDT", new BigDecimal("1000"), null, null, null, null, Instant.now())));
        MarketHealthService paused = mock(MarketHealthService.class);
        when(paused.dataLost(any())).thenReturn(true);
        DisplayPriceServiceImpl display = new DisplayPriceServiceImpl(
                provider(okx),
                new OkxProperties(
                        true,
                        URI.create("https://www.okx.com"),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(3),
                        new BigDecimal("0.05"),
                        Map.of()),
                streamed,
                latest,
                paused,
                mock(ApplicationEventPublisher.class),
                transactions,
                Clock.systemUTC());

        display.refresh(MarketType.SPOT);

        FallbackPricesResponse shown = display.prices(MarketType.SPOT);
        assertThat(shown.source()).isEqualTo(DisplaySource.OKX);
        assertThat(shown.prices())
                .filteredOn(price -> price.symbol().equals(symbol))
                .singleElement()
                .satisfies(price -> assertThat(price.lastPrice()).isEqualByComparingTo("1000"));
        assertThat(market.currentLastPrice(MarketType.SPOT, symbol))
                .as("the exchange's last price, never the fallback's")
                .hasValueSatisfying(price -> assertThat(price).isEqualByComparingTo("100"));
        assertThat(storedCandles()).as("nothing stored").isEqualTo(candlesBefore);
    }

    private long storedCandles() {
        return sql.sql("select count(*) from ohlcv where pair_id = ?")
                .param(pair)
                .query(Long.class)
                .single();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OkxClient> provider(OkxClient okx) {
        ObjectProvider<OkxClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(okx);
        return provider;
    }
}
