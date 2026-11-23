package com.cryptopilot.market.job;

import com.cryptopilot.market.service.TopicSubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SubscriptionRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Once a minute, checks the topic subscription counts (TR-34) against the broker's own subscription registry and
 * drops what the broker no longer holds, so a count whose unsubscribe or disconnect event was lost returns to the
 * truth instead of keeping a topic pushed for nobody.
 *
 * <p>The broker's registry, not {@code SimpUserRegistry}: a Guest connects without a principal, and the user registry
 * only knows sessions that have one, so it would drop every Guest's subscriptions.
 *
 * <p>Rule: TR-34.
 *
 * <p>Reference: Tanenbaum, A. S. &amp; Van Steen, M. (2017). <i>Distributed Systems</i> (3rd ed.), ch. 6
 * "Coordination" (state derived from messages is corrected against the authoritative copy).
 */
@Component
@Profile("!calibration")
public class TopicSubscriptionReconciler {

    private static final Logger log = LoggerFactory.getLogger(TopicSubscriptionReconciler.class);

    private final TopicSubscriptionService subscriptions;
    private final ObjectProvider<SimpleBrokerMessageHandler> broker;

    public TopicSubscriptionReconciler(
            TopicSubscriptionService subscriptions, ObjectProvider<SimpleBrokerMessageHandler> broker) {
        this.subscriptions = subscriptions;
        this.broker = broker;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void reconcile() {
        SimpleBrokerMessageHandler handler = broker.getIfAvailable();
        if (handler != null) {
            reconcileWith(handler.getSubscriptionRegistry());
        }
    }

    /** Reconciles the counts with this registry; how many subscriptions were dropped. */
    int reconcileWith(SubscriptionRegistry registry) {
        int dropped = subscriptions.reconcile(destination -> registry.findSubscriptions(messageTo(destination)));
        if (dropped > 0) {
            log.info("TR-34 {} topic subscriptions the broker no longer holds were dropped", dropped);
        }
        return dropped;
    }

    private static Message<byte[]> messageTo(String destination) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
