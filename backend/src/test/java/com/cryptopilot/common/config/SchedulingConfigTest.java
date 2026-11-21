package com.cryptopilot.common.config;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
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
        ObjectProvider<ThreadPoolTaskSchedulerCustomizer> none =
                new StaticApplicationContext().getBeanProvider(ThreadPoolTaskSchedulerCustomizer.class);
        ThreadPoolTaskScheduler ownApp = config.taskScheduler(none);
        ThreadPoolTaskScheduler ownBackground = config.backgroundScheduler(none);
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
