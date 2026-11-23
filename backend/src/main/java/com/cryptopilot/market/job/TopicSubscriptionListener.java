package com.cryptopilot.market.job;

import com.cryptopilot.market.service.TopicSubscriptionService;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

/**
 * Feeds the topic subscription counts from the broker's session events (TR-34): a subscribe, an unsubscribe, a
 * disconnect. A subscription the security interceptor refuses closes its session, and the disconnect takes back what
 * its event counted.
 *
 * <p>Rule: TR-34.
 */
@Component
public class TopicSubscriptionListener {

    private final TopicSubscriptionService subscriptions;

    public TopicSubscriptionListener(TopicSubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(event.getMessage());
        subscriptions.subscribed(headers.getSessionId(), headers.getSubscriptionId(), headers.getDestination());
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(event.getMessage());
        subscriptions.unsubscribed(headers.getSessionId(), headers.getSubscriptionId());
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        subscriptions.disconnected(event.getSessionId());
    }
}
