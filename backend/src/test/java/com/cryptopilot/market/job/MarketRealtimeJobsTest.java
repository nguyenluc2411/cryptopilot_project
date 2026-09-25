package com.cryptopilot.market.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.StreamMessage;
import com.cryptopilot.market.config.MarketHubProperties;
import com.cryptopilot.market.config.PriceCacheProperties;
import com.cryptopilot.market.model.PriceLookup;
import com.cryptopilot.market.service.MarketBroadcastService;
import com.cryptopilot.market.service.PriceCacheService;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/**
 * The schedules of the latest-price cache and the market topics: set up only when enabled, at the configured rhythm,
 * each running its service's push or flush.
 *
 * <p>Rule: NSF-03; TECHNICAL_DESIGN 5.6, 9 and 10.
 */
class MarketRealtimeJobsTest {

    private final List<Duration> periods = new ArrayList<>();
    private final List<Runnable> tasks = new ArrayList<>();
    private final AtomicInteger flushes = new AtomicInteger();
    private final AtomicInteger updates = new AtomicInteger();
    private final AtomicInteger overviews = new AtomicInteger();

    @Test
    void TD56_theCacheFlush_isScheduledOnlyWhenEnabled() {
        new PriceCacheJob(
                        cache(),
                        scheduler(),
                        new PriceCacheProperties(false, Duration.ofMinutes(5), Duration.ofMillis(250)))
                .start();
        assertThat(periods).isEmpty();

        new PriceCacheJob(
                        cache(),
                        scheduler(),
                        new PriceCacheProperties(true, Duration.ofMinutes(5), Duration.ofMillis(250)))
                .start();

        assertThat(periods).containsExactly(Duration.ofMillis(250));
        tasks.get(0).run();
        assertThat(flushes).hasValue(1);
    }

    @Test
    void TD9_thePushes_areScheduledOnlyWhenEnabled_atTheirRhythm() {
        new MarketHubJob(
                        broadcast(),
                        scheduler(),
                        new MarketHubProperties(false, Duration.ofSeconds(1), Duration.ofSeconds(2)))
                .start();
        assertThat(periods).isEmpty();

        new MarketHubJob(
                        broadcast(),
                        scheduler(),
                        new MarketHubProperties(true, Duration.ofSeconds(1), Duration.ofSeconds(2)))
                .start();

        assertThat(periods).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        tasks.forEach(Runnable::run);
        assertThat(updates).hasValue(1);
        assertThat(overviews).hasValue(1);
    }

    private TaskScheduler scheduler() {
        return (TaskScheduler) Proxy.newProxyInstance(
                TaskScheduler.class.getClassLoader(), new Class<?>[] {TaskScheduler.class}, (proxy, method, args) -> {
                    if (method.getName().equals("scheduleAtFixedRate") && args[1] instanceof Duration period) {
                        tasks.add((Runnable) args[0]);
                        periods.add(period);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private PriceCacheService cache() {
        return new PriceCacheService() {
            @Override
            public void record(MarketType market, StreamMessage message) {}

            @Override
            public int flush() {
                return flushes.incrementAndGet();
            }

            @Override
            public PriceLookup latest(MarketType market, String symbol) {
                return PriceLookup.missing();
            }
        };
    }

    private MarketBroadcastService broadcast() {
        return new MarketBroadcastService() {
            @Override
            public void publish(MarketType market, StreamMessage message) {}

            @Override
            public int pushUpdates() {
                return updates.incrementAndGet();
            }

            @Override
            public int pushOverviews() {
                return overviews.incrementAndGet();
            }
        };
    }
}
