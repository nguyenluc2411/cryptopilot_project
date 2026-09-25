package com.cryptopilot.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.StreamMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.model.PriceLookup;
import com.cryptopilot.market.service.impl.MarketUpdateServiceImpl;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The stream's hand-over to the cache and the topics: both receive every message, and a failure of one reaches neither
 * the other nor the stream.
 *
 * <p>Rule: NSF-03; TECHNICAL_DESIGN 5.6 and 9; ADR-005.
 */
class MarketUpdateServiceTest {

    private static final TickerMessage TICKER = new TickerMessage(
            "BTCUSDT",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            Instant.parse("2026-09-25T10:00:00Z"));

    private final List<String> received = new ArrayList<>();

    @Test
    void NSF03_bothReceiveEveryMessage() {
        MarketUpdateService updates = new MarketUpdateServiceImpl(cache(false), broadcast(false));

        updates.onUpdate(MarketType.SPOT, TICKER);

        assertThat(received).containsExactly("cache BTCUSDT", "topics BTCUSDT");
    }

    @Test
    void ADR005_aFailingCache_neitherStopsTheTopicsNorReachesTheStream() {
        MarketUpdateService updates = new MarketUpdateServiceImpl(cache(true), broadcast(false));

        assertThatCode(() -> updates.onUpdate(MarketType.SPOT, TICKER)).doesNotThrowAnyException();
        assertThat(received).containsExactly("topics BTCUSDT");
    }

    @Test
    void TD9_failingTopics_neitherStopTheCacheNorReachTheStream() {
        MarketUpdateService updates = new MarketUpdateServiceImpl(cache(false), broadcast(true));

        assertThatCode(() -> updates.onUpdate(MarketType.SPOT, TICKER)).doesNotThrowAnyException();
        assertThat(received).containsExactly("cache BTCUSDT");
    }

    private PriceCacheService cache(boolean failing) {
        return new PriceCacheService() {
            @Override
            public void record(MarketType market, StreamMessage message) {
                if (failing) {
                    throw new IllegalStateException("cache down");
                }
                received.add("cache " + message.symbol());
            }

            @Override
            public int flush() {
                return 0;
            }

            @Override
            public PriceLookup latest(MarketType market, String symbol) {
                return PriceLookup.missing();
            }
        };
    }

    private MarketBroadcastService broadcast(boolean failing) {
        return new MarketBroadcastService() {
            @Override
            public void publish(MarketType market, StreamMessage message) {
                if (failing) {
                    throw new IllegalStateException("broker down");
                }
                received.add("topics " + message.symbol());
            }

            @Override
            public int pushUpdates() {
                return 0;
            }

            @Override
            public int pushOverviews() {
                return 0;
            }
        };
    }
}
