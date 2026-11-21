package com.cryptopilot.market.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.cryptopilot.market.config.CandleBackfillProperties;
import com.cryptopilot.market.service.CandleBackfillService;
import com.cryptopilot.market.service.DroppedGapService;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The backfill runs on one thread of its own, never on the application's scheduler that pushes the market topics, and
 * that thread stops with the job; a scheduler given to the job stays its owner's.
 *
 * <p>Rule: NSF-02, NSF-03.
 */
class CandleBackfillSchedulerTest {

    @Test
    void theRunsHaveOneThreadOfTheirOwn() throws Exception {
        ThreadPoolTaskScheduler scheduler = CandleBackfillJob.ownScheduler();
        try {
            CompletableFuture<String> thread = new CompletableFuture<>();
            scheduler.schedule(() -> thread.complete(Thread.currentThread().getName()), Instant.now());

            assertThat(thread.get(5, TimeUnit.SECONDS)).startsWith(CandleBackfillJob.THREAD_PREFIX);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isEqualTo(1);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void theJobStopsTheSchedulerItBuilt_andLeavesAGivenOneRunning() {
        CandleBackfillJob owning = new CandleBackfillJob(
                mock(CandleBackfillService.class),
                mock(DroppedGapService.class),
                mock(CandleBackfillProperties.class),
                Clock.systemUTC());
        ThreadPoolTaskScheduler built = owning.ownedScheduler();
        assertThat(built.getThreadNamePrefix()).isEqualTo(CandleBackfillJob.THREAD_PREFIX);
        owning.destroy();
        assertThat(built.getScheduledExecutor().isShutdown()).isTrue();

        ThreadPoolTaskScheduler given = new ThreadPoolTaskScheduler();
        given.initialize();
        try {
            CandleBackfillJob borrowing = new CandleBackfillJob(
                    mock(CandleBackfillService.class),
                    mock(DroppedGapService.class),
                    (TaskScheduler) given,
                    mock(CandleBackfillProperties.class),
                    Clock.systemUTC());
            assertThat(borrowing.ownedScheduler()).isNull();
            borrowing.destroy();

            assertThat(given.getScheduledExecutor().isShutdown()).isFalse();
        } finally {
            given.shutdown();
        }
    }
}
