package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.service.TopicSubscriptionService;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Counts the subscriptions of each {@code /topic/...} destination (TR-34). Each session's subscriptions are kept by
 * their id, as STOMP names them, so an unsubscribe or a disconnect takes back exactly what was counted, and a repeated
 * frame counts once. Only topics are counted: the user queues reach one session and are pushed after a change only.
 *
 * <p>Rule: TR-34.
 */
@Service
public class TopicSubscriptionServiceImpl implements TopicSubscriptionService {

    private static final String TOPICS = "/topic/";

    /** Each session's subscriptions: subscription id to destination. */
    private final Map<String, Map<String, String>> sessions = new ConcurrentHashMap<>();

    /** How many subscriptions each destination has; a destination with none is removed. */
    private final Map<String, Integer> destinations = new ConcurrentHashMap<>();

    @Override
    public void subscribed(String sessionId, String subscriptionId, String destination) {
        if (sessionId == null || subscriptionId == null || destination == null || !destination.startsWith(TOPICS)) {
            return;
        }
        String previous = sessions.computeIfAbsent(sessionId, id -> new ConcurrentHashMap<>())
                .put(subscriptionId, destination);
        if (destination.equals(previous)) {
            return;
        }
        if (previous != null) {
            decrement(previous);
        }
        destinations.merge(destination, 1, Integer::sum);
    }

    @Override
    public void unsubscribed(String sessionId, String subscriptionId) {
        if (sessionId == null || subscriptionId == null) {
            return;
        }
        Map<String, String> subscriptions = sessions.get(sessionId);
        String destination = subscriptions == null ? null : subscriptions.remove(subscriptionId);
        if (destination != null) {
            decrement(destination);
        }
    }

    @Override
    public void disconnected(String sessionId) {
        if (sessionId == null) {
            return;
        }
        Map<String, String> subscriptions = sessions.remove(sessionId);
        if (subscriptions != null) {
            subscriptions.values().forEach(this::decrement);
        }
    }

    @Override
    public boolean hasSubscribers(String destination) {
        return destinations.containsKey(destination);
    }

    @Override
    public Map<String, Integer> counts() {
        return new TreeMap<>(destinations);
    }

    private void decrement(String destination) {
        destinations.computeIfPresent(destination, (key, count) -> count > 1 ? count - 1 : null);
    }
}
