package com.cryptopilot.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.job.CandleBackfillJob;
import com.cryptopilot.market.job.CoinListJob;
import com.cryptopilot.market.job.FuturesMetricsJob;
import com.cryptopilot.market.job.MarketHubJob;
import com.cryptopilot.market.job.MarketSnapshotJob;
import com.cryptopilot.market.job.MarketStreamSupervisor;
import com.cryptopilot.market.job.SymbolSyncJob;
import com.cryptopilot.news.job.NewsCrawlJob;
import com.cryptopilot.news.job.NewsSummaryJob;
import com.cryptopilot.support.TestcontainersConfig;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The jobs that push and snapshot prices and build the coin list run on the default scheduler, the ones that wait on
 * other services on the background one, and neither is the STOMP broker's; a background scheduler with every thread
 * busy holds up nothing of the default one.
 *
 * <p>Rule: NSF-02, NSF-03.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class SchedulingConfigTest {

    @Autowired
    private TaskScheduler primary;

    @Autowired
    @Qualifier(SchedulingConfig.APP)
    private ThreadPoolTaskScheduler app;

    @Autowired
    @Qualifier(SchedulingConfig.BACKGROUND)
    private ThreadPoolTaskScheduler background;

    @Autowired
    @Qualifier(SchedulingConfig.BACKFILL)
    private ThreadPoolTaskScheduler backfill;

    @Autowired
    private CandleBackfillJob candleBackfill;

    @Autowired
    @Qualifier("messageBrokerTaskScheduler")
    private TaskScheduler broker;

    @Autowired
    private MarketHubJob hub;

    @Autowired
    private MarketSnapshotJob snapshots;

    @Autowired
    private CoinListJob coinList;

    @Autowired
    private MarketStreamSupervisor streams;

    @Autowired
    private SymbolSyncJob symbolSync;

    @Autowired
    private FuturesMetricsJob futuresMetrics;

    @Autowired
    private NewsCrawlJob newsCrawl;

    @Autowired
    private NewsSummaryJob newsSummary;

    @Test
    void theDefaultScheduler_isTheApplicationsOwn_notTheBrokers() {
        assertThat(primary).isSameAs(app).isNotSameAs(broker);
        assertThat(app.getThreadNamePrefix()).isEqualTo("app-scheduler-");
        assertThat(background.getThreadNamePrefix()).isEqualTo("background-");
        assertThat(backfill.getThreadNamePrefix()).isEqualTo("candle-backfill-");
        assertThat(backfill.getPoolSize()).isLessThanOrEqualTo(1);
        assertThat(backfill.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
        assertThat(backfill.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy())
                .isTrue();
        assertThat(ReflectionTestUtils.getField(candleBackfill, "scheduler")).isSameAs(backfill);
    }

    /** A backfill task that fails reaches the application's error handler (UC-52), as every other job's does. */
    @Test
    void aFailedBackfillTask_reachesTheErrorHandler() throws Exception {
        CompletableFuture<Throwable> handled = new CompletableFuture<>();
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("recordFailures", (ThreadPoolTaskSchedulerCustomizer)
                scheduler -> scheduler.setErrorHandler(handled::complete));
        ThreadPoolTaskScheduler ownBackfill = new SchedulingConfig()
                .backfillScheduler(new ThreadPoolTaskSchedulerBuilder()
                        .customizers(context.getBeanProvider(ThreadPoolTaskSchedulerCustomizer.class)
                                .orderedStream()
                                .toList()));
        ownBackfill.initialize();
        try {
            ownBackfill.schedule(
                    () -> {
                        throw new IllegalStateException("exchange down");
                    },
                    Instant.now());

            assertThat(handled.get(5, TimeUnit.SECONDS)).hasMessage("exchange down");
            assertThat(handled.get().getStackTrace()).isNotEmpty();
        } finally {
            ownBackfill.shutdown();
        }
    }

    /**
     * The schedulers come from Spring Boot's builder: spring.task.scheduling.* sets the default one as it is, and the
     * background and backfill ones keep its settings (here the shutdown wait) with their own size and prefix.
     */
    @Test
    void theTaskSchedulingProperties_reachEveryScheduler() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(SchedulingConfig.class)
                .withPropertyValues(
                        "spring.task.scheduling.pool.size=3",
                        "spring.task.scheduling.thread-name-prefix=custom-",
                        "spring.task.scheduling.shutdown.await-termination=true")
                .run(context -> {
                    ThreadPoolTaskScheduler ownApp =
                            context.getBean(SchedulingConfig.APP, ThreadPoolTaskScheduler.class);
                    ThreadPoolTaskScheduler ownBackground =
                            context.getBean(SchedulingConfig.BACKGROUND, ThreadPoolTaskScheduler.class);
                    ThreadPoolTaskScheduler ownBackfill =
                            context.getBean(SchedulingConfig.BACKFILL, ThreadPoolTaskScheduler.class);

                    assertThat(ownApp.getThreadNamePrefix()).isEqualTo("custom-");
                    assertThat(ownApp.getScheduledThreadPoolExecutor().getCorePoolSize())
                            .isEqualTo(3);
                    assertThat(ownBackground.getThreadNamePrefix()).isEqualTo("background-");
                    assertThat(ownBackground.getScheduledThreadPoolExecutor().getCorePoolSize())
                            .isEqualTo(SchedulingConfig.BACKGROUND_THREADS);
                    assertThat(ownBackfill.getThreadNamePrefix()).isEqualTo("candle-backfill-");
                    for (ThreadPoolTaskScheduler scheduler :
                            new ThreadPoolTaskScheduler[] {ownApp, ownBackground, ownBackfill}) {
                        assertThat(ReflectionTestUtils.getField(scheduler, "waitForTasksToCompleteOnShutdown"))
                                .as(scheduler.getThreadNamePrefix())
                                .isEqualTo(true);
                    }
                });
    }

    @Test
    void thePriceJobs_runOnTheDefaultScheduler_andTheJobsWaitingOnOthers_onTheBackgroundOne() {
        for (Object job : new Object[] {hub, snapshots, coinList, streams}) {
            assertThat(ReflectionTestUtils.getField(job, "scheduler"))
                    .as(job.getClass().getSimpleName())
                    .isSameAs(app);
        }
        for (Object job : new Object[] {symbolSync, futuresMetrics, newsCrawl, newsSummary}) {
            assertThat(ReflectionTestUtils.getField(job, "scheduler"))
                    .as(job.getClass().getSimpleName())
                    .isSameAs(background);
        }
    }

    @Test
    void aDefaultTask_runsWhileEveryBackgroundThreadIsBusy() throws Exception {
        SchedulingConfig config = new SchedulingConfig();
        ThreadPoolTaskSchedulerBuilder fromProperties =
                new ThreadPoolTaskSchedulerBuilder().poolSize(8).threadNamePrefix("app-scheduler-");
        ThreadPoolTaskScheduler ownApp = config.taskScheduler(fromProperties);
        ThreadPoolTaskScheduler ownBackground = config.backgroundScheduler(fromProperties);
        ownApp.initialize();
        ownBackground.initialize();
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < SchedulingConfig.BACKGROUND_THREADS + 2; i++) {
                ownBackground.schedule(
                        () -> {
                            try {
                                release.await();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        Instant.now());
            }
            CompletableFuture<String> ran = new CompletableFuture<>();
            ownApp.schedule(() -> ran.complete(Thread.currentThread().getName()), Instant.now());

            assertThat(ran.get(2, TimeUnit.SECONDS)).startsWith("app-scheduler-");
        } finally {
            release.countDown();
            ownApp.shutdown();
            ownBackground.shutdown();
        }
    }
}
