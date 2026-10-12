package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.BookTop;
import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.OrderBookDepth;
import com.cryptopilot.market.OrderBookDepth.PriceLevel;
import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.MutableTestClock;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the trading streams leave in memory (TR-03): the latest best bid and ask and depth by book update id, served
 * only while fresh; the latest trades, newest first and bounded; each market and pair apart; and a pair no longer
 * streamed dropped (BR-07).
 *
 * <p>Rule: TR-03; BR-07.
 */
class TradingMarketDataTest {

    private static final Instant AT = Instant.parse("2026-10-10T08:00:00Z");
    private static final UUID BTC = UUID.randomUUID();
    private static final UUID ETH = UUID.randomUUID();

    private final MutableTestClock clock = new MutableTestClock(AT);
    private final TradingMarketData data = new TradingMarketData(properties(3), clock);

    @Test
    void TR03_theBookTopOfTheLatestUpdate_isKept_evenWhenAnOlderOneArrivesLate() {
        data.recordBookTop(top(BTC, 10, "100.1"));
        data.recordBookTop(top(BTC, 9, "99.9"));

        assertThat(data.currentBookTop(MarketType.SPOT, BTC).orElseThrow().bidPrice())
                .isEqualByComparingTo("100.1");
        assertThat(data.currentBookTop(MarketType.FUTURES, BTC)).isEmpty();
        assertThat(data.currentBookTop(MarketType.SPOT, ETH)).isEmpty();
    }

    @Test
    void TR03_aBookTopOrDepthOlderThanTheMaxAge_isNotServedAsCurrent() {
        data.recordBookTop(top(BTC, 1, "100"));
        data.recordDepth(depth(BTC, 1));

        clock.set(AT.plusSeconds(10));
        assertThat(data.currentBookTop(MarketType.SPOT, BTC)).isPresent();
        assertThat(data.currentDepth(MarketType.SPOT, BTC)).isPresent();

        clock.set(AT.plusSeconds(10).plusMillis(1));
        assertThat(data.currentBookTop(MarketType.SPOT, BTC)).isEmpty();
        assertThat(data.currentDepth(MarketType.SPOT, BTC)).isEmpty();
    }

    @Test
    void TR03_theDepthOfTheLatestUpdate_isKept() {
        data.recordDepth(depth(BTC, 42));
        data.recordDepth(depth(BTC, 41));

        assertThat(data.currentDepth(MarketType.SPOT, BTC).orElseThrow().lastUpdateId())
                .isEqualTo(42);
    }

    @Test
    void TR03_theLatestTrades_areServedNewestFirst_andOnlyTheConfiguredNumberIsKept() {
        for (long id = 1; id <= 5; id++) {
            data.recordTrade(trade(BTC, id));
        }
        data.recordTrade(trade(ETH, 100));

        assertThat(data.recentTrades(MarketType.SPOT, BTC, 10))
                .extracting(MarketTrade::aggregateTradeId)
                .containsExactly(5L, 4L, 3L);
        assertThat(data.recentTrades(MarketType.SPOT, BTC, 2))
                .extracting(MarketTrade::aggregateTradeId)
                .containsExactly(5L, 4L);
        assertThat(data.recentTrades(MarketType.FUTURES, BTC, 10)).isEmpty();
    }

    @Test
    void BR07_aPairNoLongerStreamed_isDropped_onItsMarketOnly() {
        data.recordBookTop(top(BTC, 1, "100"));
        data.recordDepth(depth(BTC, 1));
        data.recordTrade(trade(BTC, 1));
        data.recordBookTop(top(ETH, 1, "4000"));

        data.retainOnly(MarketType.FUTURES, List.of());
        assertThat(data.currentBookTop(MarketType.SPOT, BTC)).isPresent();

        data.retainOnly(MarketType.SPOT, List.of(ETH));
        assertThat(data.currentBookTop(MarketType.SPOT, BTC)).isEmpty();
        assertThat(data.currentDepth(MarketType.SPOT, BTC)).isEmpty();
        assertThat(data.recentTrades(MarketType.SPOT, BTC, 10)).isEmpty();
        assertThat(data.currentBookTop(MarketType.SPOT, ETH)).isPresent();
    }

    private BookTop top(UUID pair, long updateId, String bid) {
        BigDecimal price = new BigDecimal(bid);
        return new BookTop(
                MarketType.SPOT,
                pair,
                updateId,
                price,
                BigDecimal.ONE,
                price.add(BigDecimal.ONE),
                BigDecimal.TEN,
                clock.instant());
    }

    private OrderBookDepth depth(UUID pair, long updateId) {
        return new OrderBookDepth(
                MarketType.SPOT,
                pair,
                updateId,
                List.of(new PriceLevel(new BigDecimal("100"), BigDecimal.ONE)),
                List.of(new PriceLevel(new BigDecimal("101"), BigDecimal.ONE)),
                clock.instant());
    }

    private static MarketTrade trade(UUID pair, long id) {
        return new MarketTrade(
                MarketType.SPOT, pair, id, new BigDecimal("100"), BigDecimal.ONE, AT.plusMillis(id), false, AT);
    }

    static BinanceStreamProperties properties(int recentTrades) {
        return new BinanceStreamProperties(
                true,
                URI.create("wss://stream.binance.com:9443"),
                URI.create("wss://fstream.binance.com/market"),
                100,
                Duration.ofSeconds(10),
                new BinanceStreamProperties.Reconnect(Duration.ofSeconds(1), Duration.ofSeconds(60), 20),
                Duration.ofHours(23),
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                Duration.ofMinutes(2),
                4,
                10000,
                new BinanceStreamProperties.Trading(true, 20, Duration.ofSeconds(10), recentTrades));
    }
}
