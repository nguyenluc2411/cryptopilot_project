package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.support.MutableTestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The topic subscription counts when the broker's events of one session arrive out of order (TR-34).
 *
 * <p>Invariants: (1) a subscribe processed after its session's disconnect counts nothing; (2) once the remembered
 * disconnect has expired, the id counts again; (3) the reconcile drops a settled subscription the broker no longer
 * holds, keeps one it holds and one younger than the grace; (4) racing subscribe and disconnect of one session always
 * end at zero.
 *
 * <p>Rule: TR-34.
 */
class TopicSubscriptionServiceImplTest {

    private static final Instant T0 = Instant.parse("2026-11-22T08:00:00Z");
    private static final String BTC = "/topic/ticker.SPOT.BTCUSDT";
    private static final String ETH = "/topic/ticker.SPOT.ETHUSDT";

    private final MutableTestClock clock = new MutableTestClock(T0);
    private final TopicSubscriptionServiceImpl subscriptions = new TopicSubscriptionServiceImpl(clock);

    @Test
    void TR34_aSubscribeAfterItsSessionsDisconnect_leavesNoCount() {
        subscriptions.subscribed("s1", "sub-0", BTC);
        subscriptions.disconnected("s1");

        subscriptions.subscribed("s1", "sub-1", BTC);
        subscriptions.subscribed("s1", "sub-0", ETH);

        assertThat(subscriptions.counts()).isEmpty();
        assertThat(subscriptions.hasSubscribers(BTC)).isFalse();
    }

    @Test
    void TR34_aDisconnectFirst_alsoBlocksTheLateSubscribe() {
        subscriptions.disconnected("s9");
        subscriptions.subscribed("s9", "sub-0", BTC);

        assertThat(subscriptions.counts()).isEmpty();
    }

    @Test
    void TR34_aRememberedDisconnect_expires() {
        subscriptions.disconnected("s1");
        clock.set(T0.plus(TopicSubscriptionServiceImpl.GONE_TTL).plusSeconds(1));
        subscriptions.disconnected("other");

        subscriptions.subscribed("s1", "sub-0", BTC);

        assertThat(subscriptions.counts()).containsExactly(Map.entry(BTC, 1));
    }

    @Test
    void TR34_theLastUnsubscribe_forgetsTheSession() {
        subscriptions.subscribed("s1", "sub-0", BTC);
        subscriptions.unsubscribed("s1", "sub-0");
        subscriptions.unsubscribed("s1", "sub-0");
        subscriptions.unsubscribed(null, "sub-0");

        clock.set(T0.plus(Duration.ofMinutes(2)));
        assertThat(subscriptions.reconcile(destination -> Map.of())).isZero();
        assertThat(subscriptions.counts()).isEmpty();
    }

    @Test
    void TR34_theReconcile_dropsWhatTheBrokerNoLongerHolds_afterTheGrace() {
        subscriptions.subscribed("stale", "sub-0", BTC);
        subscriptions.subscribed("live", "sub-0", BTC);
        subscriptions.subscribed("live", "sub-1", ETH);
        clock.set(T0.plus(TopicSubscriptionServiceImpl.RECONCILE_GRACE).plusSeconds(1));
        subscriptions.subscribed("young", "sub-0", ETH);
        Map<String, Map<String, List<String>>> broker = Map.of(
                BTC, Map.of("live", List.of("sub-0")),
                ETH, Map.of("live", List.of("sub-9")));

        int dropped = subscriptions.reconcile(destination -> broker.getOrDefault(destination, Map.of()));

        assertThat(dropped).as("stale/sub-0 and live/sub-1").isEqualTo(2);
        assertThat(subscriptions.counts())
                .as("young/sub-0 is within the grace")
                .containsExactly(Map.entry(BTC, 1), Map.entry(ETH, 1));
    }

    @Test
    void TR34_aSubscriptionReplacedDuringTheReconcile_isKept() {
        subscriptions.subscribed("s1", "sub-0", BTC);
        clock.set(T0.plus(Duration.ofMinutes(1)));

        int dropped = subscriptions.reconcile(destination -> {
            // The session moves its id to another topic while the broker is asked.
            subscriptions.subscribed("s1", "sub-0", ETH);
            return Map.of();
        });

        assertThat(dropped).isZero();
        assertThat(subscriptions.counts()).containsExactly(Map.entry(ETH, 1));
    }

    @Test
    void TR34_racingSubscribesAndADisconnect_ofOneSession_endAtZero() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 200; round++) {
                String session = "race-" + round;
                CountDownLatch start = new CountDownLatch(1);
                for (int i = 0; i < 3; i++) {
                    String id = "sub-" + i;
                    threads.submit(() -> {
                        start.await();
                        subscriptions.subscribed(session, id, BTC);
                        return null;
                    });
                }
                threads.submit(() -> {
                    start.await();
                    subscriptions.disconnected(session);
                    return null;
                });
                start.countDown();
            }
        } finally {
            threads.shutdown();
            assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(subscriptions.counts()).isEmpty();
    }
}
