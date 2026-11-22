package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.service.TopicSubscriptionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Counts the subscriptions of each {@code /topic/...} destination (TR-34). Each session's subscriptions are kept by
 * their id, as STOMP names them, so an unsubscribe or a disconnect takes back exactly what was counted, and a repeated
 * frame counts once. Only topics are counted: the user queues reach one session and are pushed after a change only.
 *
 * <p>The broker's events for one session arrive on different threads, a subscribe possibly after the disconnect of its
 * session. So:
 *
 * <ul>
 *   <li>every change of one session is one atomic step on that session's entry ({@code compute} on the sessions map),
 *       counts included;
 *   <li>a disconnected session is remembered for {@link #GONE_TTL}, and a subscribe that arrives for it meanwhile is
 *       ignored, so a late frame cannot recreate an entry nobody will ever take back;
 *   <li>{@link #reconcile} drops what the broker itself no longer holds, as a safety net for an event lost
 *       altogether; a subscription younger than {@link #RECONCILE_GRACE} is left alone, since the broker registers it
 *       just after its event.
 * </ul>
 *
 * <p>Rule: TR-34.
 *
 * <p>Reference: Tanenbaum, A. S. &amp; Van Steen, M. (2017). <i>Distributed Systems</i> (3rd ed.), ch. 6
 * "Coordination" (events of one process delivered out of order; a removal kept as a marker so a delayed message cannot
 * undo it). Goetz, B. et al. (2006). <i>Java Concurrency in Practice</i>. Addison-Wesley, §2.3 (compound actions made
 * atomic).
 */
@Service
public class TopicSubscriptionServiceImpl implements TopicSubscriptionService {

    static final Duration GONE_TTL = Duration.ofMinutes(5);
    static final Duration RECONCILE_GRACE = Duration.ofSeconds(30);
    private static final String TOPICS = "/topic/";

    private final Clock clock;

    /** Each session's subscriptions by id; only changed inside {@code compute} on the session's key. */
    private final Map<String, Map<String, Subscription>> sessions = new ConcurrentHashMap<>();

    /** The sessions disconnected within {@link #GONE_TTL}, with when. */
    private final Map<String, Instant> gone = new ConcurrentHashMap<>();

    /** How many subscriptions each destination has; a destination with none is removed. */
    private final Map<String, Integer> destinations = new ConcurrentHashMap<>();

    @Autowired
    public TopicSubscriptionServiceImpl(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void subscribed(String sessionId, String subscriptionId, String destination) {
        if (sessionId == null || subscriptionId == null || destination == null || !destination.startsWith(TOPICS)) {
            return;
        }
        Instant now = clock.instant();
        sessions.compute(sessionId, (id, held) -> {
            if (gone.containsKey(id)) {
                return held;
            }
            Map<String, Subscription> subscriptions = held == null ? new HashMap<>() : held;
            Subscription previous = subscriptions.get(subscriptionId);
            if (previous != null && previous.destination().equals(destination)) {
                return subscriptions;
            }
            if (previous != null) {
                decrement(previous.destination());
            }
            subscriptions.put(subscriptionId, new Subscription(destination, now));
            destinations.merge(destination, 1, Integer::sum);
            return subscriptions;
        });
    }

    @Override
    public void unsubscribed(String sessionId, String subscriptionId) {
        if (sessionId == null || subscriptionId == null) {
            return;
        }
        sessions.computeIfPresent(sessionId, (id, subscriptions) -> {
            Subscription removed = subscriptions.remove(subscriptionId);
            if (removed != null) {
                decrement(removed.destination());
            }
            return subscriptions.isEmpty() ? null : subscriptions;
        });
    }

    @Override
    public void disconnected(String sessionId) {
        if (sessionId == null) {
            return;
        }
        Instant now = clock.instant();
        forgetGoneBefore(now.minus(GONE_TTL));
        sessions.compute(sessionId, (id, subscriptions) -> {
            gone.put(id, now);
            if (subscriptions != null) {
                subscriptions.values().forEach(each -> decrement(each.destination()));
            }
            return null;
        });
    }

    @Override
    public int reconcile(Function<String, Map<String, ? extends Collection<String>>> brokerSubscriptions) {
        Instant now = clock.instant();
        forgetGoneBefore(now.minus(GONE_TTL));
        Instant settled = now.minus(RECONCILE_GRACE);
        Map<String, List<Held>> byDestination = new HashMap<>();
        sessions.forEach((sessionId, subscriptions) -> {
            // A snapshot under the session's lock: its map is only safe to read there.
            sessions.computeIfPresent(sessionId, (id, current) -> {
                current.forEach((subscriptionId, subscription) -> {
                    if (!subscription.since().isAfter(settled)) {
                        byDestination
                                .computeIfAbsent(subscription.destination(), key -> new ArrayList<>())
                                .add(new Held(id, subscriptionId, subscription));
                    }
                });
                return current;
            });
        });
        int dropped = 0;
        for (Map.Entry<String, List<Held>> entry : byDestination.entrySet()) {
            Map<String, ? extends Collection<String>> broker = brokerSubscriptions.apply(entry.getKey());
            for (Held held : entry.getValue()) {
                Collection<String> ids = broker.get(held.sessionId());
                if ((ids == null || !ids.contains(held.subscriptionId())) && drop(held)) {
                    dropped++;
                }
            }
        }
        return dropped;
    }

    @Override
    public boolean hasSubscribers(String destination) {
        return destinations.containsKey(destination);
    }

    @Override
    public Map<String, Integer> counts() {
        return new TreeMap<>(destinations);
    }

    /** Drops the subscription if the session still holds exactly it, not a newer one under its id. */
    private boolean drop(Held held) {
        boolean[] dropped = {false};
        sessions.computeIfPresent(held.sessionId(), (id, subscriptions) -> {
            if (subscriptions.get(held.subscriptionId()) == held.subscription()) {
                subscriptions.remove(held.subscriptionId());
                decrement(held.subscription().destination());
                dropped[0] = true;
            }
            return subscriptions.isEmpty() ? null : subscriptions;
        });
        return dropped[0];
    }

    private void forgetGoneBefore(Instant cutoff) {
        gone.values().removeIf(at -> at.isBefore(cutoff));
    }

    private void decrement(String destination) {
        destinations.computeIfPresent(destination, (key, count) -> count > 1 ? count - 1 : null);
    }

    private record Subscription(String destination, Instant since) {}

    private record Held(String sessionId, String subscriptionId, Subscription subscription) {}
}
