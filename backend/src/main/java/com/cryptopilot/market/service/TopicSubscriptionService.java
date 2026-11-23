package com.cryptopilot.market.service;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

/**
 * Who listens to which market topic (TR-34): the subscriptions of every session to each {@code /topic/...}
 * destination, counted as the broker registers and drops them, so a topic nobody listens to is not even serialised.
 *
 * <p>Rule: TR-34; plan section 12.
 */
public interface TopicSubscriptionService {

    /** A session subscribed, under its own subscription id, to a destination. */
    void subscribed(String sessionId, String subscriptionId, String destination);

    /** A session dropped one of its subscriptions. */
    void unsubscribed(String sessionId, String subscriptionId);

    /** A session ended: all its subscriptions go. */
    void disconnected(String sessionId);

    /**
     * Drops the subscriptions counted for a while that the broker no longer holds: a safety net for an unsubscribe or
     * a disconnect whose event never came.
     *
     * @param brokerSubscriptions for a destination, the broker's subscriptions to it: session id to subscription ids
     * @return how many subscriptions were dropped
     */
    int reconcile(Function<String, Map<String, ? extends Collection<String>>> brokerSubscriptions);

    /** Whether any session listens to the destination. */
    boolean hasSubscribers(String destination);

    /** The destinations listened to now, each with how many subscriptions it has. */
    Map<String, Integer> counts();
}
