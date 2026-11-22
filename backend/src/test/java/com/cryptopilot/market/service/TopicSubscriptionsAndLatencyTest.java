package com.cryptopilot.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.impl.MarketBroadcastServiceImpl;
import com.cryptopilot.market.service.impl.TopicSubscriptionServiceImpl;
import com.cryptopilot.support.MutableTestClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * TR-34 and TR-36: the subscriptions of each market topic are counted, a topic nobody listens to is not pushed, and
 * each push is timed from the exchange's instant.
 *
 * <p>Invariants: (1) a destination's count is its live subscriptions, each counted once by its session and id; (2) an
 * unsubscribe or a disconnect takes back exactly what was counted; (3) only {@code /topic/} destinations are counted;
 * (4) a destination without subscriptions is not handed to the broker; (5) each push handed over is timed once.
 *
 * <p>Rule: TR-34, TR-36.
 */
class TopicSubscriptionsAndLatencyTest {

    private static final Instant AT = Instant.parse("2026-11-22T08:00:00Z");
    private static final String BTC = "/topic/ticker.SPOT.BTCUSDT";
    private static final String ETH = "/topic/ticker.SPOT.ETHUSDT";

    private final TopicSubscriptionServiceImpl subscriptions =
            new TopicSubscriptionServiceImpl(java.time.Clock.systemUTC());

    @Test
    void TR34_eachSubscription_isCountedOnce_andTakenBackByItsUnsubscribeOrDisconnect() {
        subscriptions.subscribed("s1", "sub-0", BTC);
        subscriptions.subscribed("s1", "sub-0", BTC);
        subscriptions.subscribed("s1", "sub-1", ETH);
        subscriptions.subscribed("s2", "sub-0", BTC);
        subscriptions.subscribed("s2", "sub-9", "/user/queue/orders");

        assertThat(subscriptions.counts()).containsExactly(Map.entry(BTC, 2), Map.entry(ETH, 1));

        subscriptions.unsubscribed("s1", "sub-0");
        subscriptions.unsubscribed("s1", "sub-0");
        assertThat(subscriptions.counts()).containsEntry(BTC, 1);

        subscriptions.disconnected("s2");
        assertThat(subscriptions.hasSubscribers(BTC)).isFalse();
        assertThat(subscriptions.counts()).containsExactly(Map.entry(ETH, 1));

        subscriptions.subscribed("s1", "sub-1", BTC);
        assertThat(subscriptions.counts())
                .as("an id reused for another topic moves")
                .containsExactly(Map.entry(BTC, 1));
        subscriptions.disconnected("s1");
        subscriptions.disconnected("nobody");
        subscriptions.subscribed(null, "x", BTC);
        assertThat(subscriptions.counts()).isEmpty();
    }

    @Test
    void TR34_aTopicNobodyListensTo_isNotPushed_andTR36_eachPushIsTimed() {
        List<String> sent = new ArrayList<>();
        MessageChannel recorder = (message, timeout) -> {
            sent.add(SimpMessageHeaderAccessor.getDestination(message.getHeaders()));
            return true;
        };
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MutableTestClock clock = new MutableTestClock(AT.plusMillis(250));
        MarketBroadcastService broadcast =
                new MarketBroadcastServiceImpl(new SimpMessagingTemplate(recorder), clock, subscriptions, meters);
        subscriptions.subscribed("s1", "sub-0", BTC);

        broadcast.publish(MarketType.SPOT, ticker("BTCUSDT", AT));
        broadcast.publish(MarketType.SPOT, ticker("ETHUSDT", AT));

        assertThat(broadcast.pushUpdates()).isEqualTo(1);
        assertThat(sent).containsExactly(BTC);
        Timer timer = meters.get(MarketBroadcastServiceImpl.PUSH_LATENCY)
                .tag("kind", "ticker")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250.0);

        clock.set(AT.minus(Duration.ofSeconds(1)));
        broadcast.publish(MarketType.SPOT, ticker("BTCUSDT", AT.plusMillis(1)));
        broadcast.pushUpdates();
        assertThat(timer.count())
                .as("a clock behind the exchange's is not timed")
                .isEqualTo(1);
    }

    private static TickerMessage ticker(String symbol, Instant at) {
        BigDecimal price = new BigDecimal("100");
        return new TickerMessage(
                symbol, price, price, price, price, price, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, at);
    }
}
