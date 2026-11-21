package com.cryptopilot.common.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The schedulers of the background jobs, apart from the STOMP broker's.
 *
 * <p>Without a scheduler of its own, the application had only the one the WebSocket broker builds
 * ({@code messageBrokerTaskScheduler}, one thread per core), so every job ran on it: the market topics, the price
 * snapshots and the coin list together with the jobs that spend long minutes on exchange and provider calls. When those
 * took every thread, the prices stopped. Now:
 *
 * <ul>
 *   <li>{@link #APP} ({@code app-scheduler-*}), the default one, for the short jobs: pushing the market topics, the
 *       snapshots, the coin list, the sweeps, the paper and trading jobs, {@code @Scheduled} methods.
 *   <li>{@link #BACKGROUND} ({@code background-*}), asked for by name, for the jobs that wait on another service: the
 *       symbol synchronisation, the futures metrics, CoinPaprika, the Fear &amp; Greed Index, the news. Busy as they may
 *       be, the default scheduler keeps its threads.
 * </ul>
 *
 * <p>{@link #BACKFILL} ({@code candle-backfill-*}), one thread, is the candle backfill's own: a run holds its thread
 * through many exchange calls. Every scheduler gets the customizers of the application, among them the one that
 * records a job's failure in the system log (UC-52).
 *
 * <p>Rule: NSF-02, NSF-03; UC-52.
 */
@Configuration(proxyBeanMethods = false)
public class SchedulingConfig {

    /** The name of the default scheduler; Spring's scheduling also looks for a scheduler by this name. */
    public static final String APP = "taskScheduler";

    /** The name of the scheduler of the jobs that wait on another service. */
    public static final String BACKGROUND = "backgroundScheduler";

    /** The name of the candle backfill's scheduler. */
    public static final String BACKFILL = "backfillScheduler";

    /** Threads of the default scheduler: its jobs are short, a few at a time. */
    static final int APP_THREADS = 8;

    /** Threads of the background scheduler: a few jobs waiting on the network at once. */
    static final int BACKGROUND_THREADS = 4;

    @Bean(APP)
    @Primary
    ThreadPoolTaskScheduler taskScheduler(ObjectProvider<ThreadPoolTaskSchedulerCustomizer> customizers) {
        return scheduler("app-scheduler-", APP_THREADS, customizers);
    }

    @Bean(BACKGROUND)
    ThreadPoolTaskScheduler backgroundScheduler(ObjectProvider<ThreadPoolTaskSchedulerCustomizer> customizers) {
        return scheduler("background-", BACKGROUND_THREADS, customizers);
    }

    @Bean(BACKFILL)
    ThreadPoolTaskScheduler backfillScheduler(ObjectProvider<ThreadPoolTaskSchedulerCustomizer> customizers) {
        ThreadPoolTaskScheduler scheduler = scheduler("candle-backfill-", 1, customizers);
        // A replaced continuation leaves the queue at once.
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private static ThreadPoolTaskScheduler scheduler(
            String prefix, int threads, ObjectProvider<ThreadPoolTaskSchedulerCustomizer> customizers) {
        return new ThreadPoolTaskSchedulerBuilder()
                .poolSize(threads)
                .threadNamePrefix(prefix)
                .customizers(customizers.orderedStream().toList())
                .build();
    }
}
