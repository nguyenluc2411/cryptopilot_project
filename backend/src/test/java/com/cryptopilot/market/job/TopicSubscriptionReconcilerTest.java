package com.cryptopilot.market.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.service.TopicSubscriptionService;
import com.cryptopilot.market.service.impl.TopicSubscriptionServiceImpl;
import com.cryptopilot.support.MutableTestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;

/**
 * TR-34: the counts are checked against the broker's own subscription registry, which knows Guests' sessions too.
 *
 * <p>Invariants: (1) a session the broker no longer holds is dropped once settled; (2) one it holds stays; (3) without
 * a simple broker nothing is done.
 *
 * <p>Rule: TR-34.
 */
class TopicSubscriptionReconcilerTest {

    private static final Instant T0 = Instant.parse("2026-11-22T08:00:00Z");
    private static final String BTC = "/topic/ticker.SPOT.BTCUSDT";

    private final MutableTestClock clock = new MutableTestClock(T0);
    private final TopicSubscriptionServiceImpl counts = new TopicSubscriptionServiceImpl(clock);

    @Test
    void TR34_aStaleSession_isDropped_andAHeldOneStays() {
        DefaultSubscriptionRegistry registry = new DefaultSubscriptionRegistry();
        registry.registerSubscription(subscribe("guest", "sub-0", BTC));
        counts.subscribed("guest", "sub-0", BTC);
        counts.subscribed("lost", "sub-0", BTC);
        clock.set(T0.plus(Duration.ofMinutes(1)));

        @SuppressWarnings("unchecked")
        ObjectProvider<SimpleBrokerMessageHandler> broker = mock(ObjectProvider.class);
        SimpleBrokerMessageHandler handler = mock(SimpleBrokerMessageHandler.class);
        when(broker.getIfAvailable()).thenReturn(handler);
        when(handler.getSubscriptionRegistry()).thenReturn(registry);

        new TopicSubscriptionReconciler(counts, broker).reconcile();

        assertThat(counts.counts()).containsExactly(Map.entry(BTC, 1));
        assertThat(new TopicSubscriptionReconciler(counts, broker).reconcileWith(registry))
                .isZero();
    }

    @Test
    void TR34_withoutASimpleBroker_nothingIsDone() {
        TopicSubscriptionService service = mock(TopicSubscriptionService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SimpleBrokerMessageHandler> broker = mock(ObjectProvider.class);

        new TopicSubscriptionReconciler(service, broker).reconcile();

        verify(service, never()).reconcile(org.mockito.ArgumentMatchers.any());
    }

    private static org.springframework.messaging.Message<byte[]> subscribe(
            String session, String subscription, String destination) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
        headers.setSessionId(session);
        headers.setSubscriptionId(subscription);
        headers.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
