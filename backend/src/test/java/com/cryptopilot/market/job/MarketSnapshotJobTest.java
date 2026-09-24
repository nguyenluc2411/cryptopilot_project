package com.cryptopilot.market.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.service.LatestMarketData;
import com.cryptopilot.market.service.MarketSnapshotService;
import com.cryptopilot.market.service.SnapshotRun;
import com.cryptopilot.support.MutableTestClock;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/**
 * The snapshot schedule: every minute from the next whole minute when the streams are enabled, nothing
 * otherwise, and a failed run never escaping into the scheduler.
 *
 * <p>Rule: NSF-03 (PERIODIC snapshots every minute); TECHNICAL_DESIGN 7.1 step 7.
 */
class MarketSnapshotJobTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:03:17Z");

    private final MutableTestClock clock = new MutableTestClock(NOW);
    private final List<Object[]> scheduled = new ArrayList<>();

    @Test
    void NSF03_enabled_snapshotsEveryMinuteFromTheNextWholeMinute() {
        new MarketSnapshotJob(service(null), scheduler(), properties(true), clock).start();

        assertThat(scheduled).singleElement().satisfies(call -> {
            assertThat(call[0]).isEqualTo(Instant.parse("2026-09-24T10:04:00Z"));
            assertThat(call[1]).isEqualTo(Duration.ofSeconds(60));
        });
    }

    @Test
    void NSF03_disabled_schedulesNothing() {
        new MarketSnapshotJob(service(null), scheduler(), properties(false), clock).start();

        assertThat(scheduled).isEmpty();
    }

    @Test
    void NSF03_aRun_reportsWhatWasWritten_andAFailureIsContained() {
        SnapshotRun written = new SnapshotRun(NOW, 2, 1, 0);

        assertThat(new MarketSnapshotJob(service(written), scheduler(), properties(true), clock).run())
                .contains(written);
        assertThat(new MarketSnapshotJob(service(null), scheduler(), properties(true), clock).run())
                .isEmpty();
    }

    /** A service that answers the given run, or fails when there is none. */
    private MarketSnapshotService service(SnapshotRun run) {
        return new MarketSnapshotService(new LatestMarketData(), null, properties(true), clock) {
            @Override
            public SnapshotRun writePeriodic() {
                if (run == null) {
                    throw new IllegalStateException("the database is down");
                }
                return run;
            }
        };
    }

    private TaskScheduler scheduler() {
        return (TaskScheduler) Proxy.newProxyInstance(
                TaskScheduler.class.getClassLoader(), new Class<?>[] {TaskScheduler.class}, (proxy, method, args) -> {
                    if (method.getName().equals("scheduleAtFixedRate") && args.length == 3) {
                        scheduled.add(new Object[] {args[1], args[2]});
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static BinanceStreamProperties properties(boolean enabled) {
        return new BinanceStreamProperties(
                enabled,
                URI.create("ws://127.0.0.1:1"),
                URI.create("ws://127.0.0.1:1/market"),
                100,
                Duration.ofSeconds(2),
                new BinanceStreamProperties.Reconnect(Duration.ofSeconds(1), Duration.ofSeconds(60), 20),
                Duration.ofHours(23),
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                Duration.ofMinutes(2),
                4,
                100);
    }
}
