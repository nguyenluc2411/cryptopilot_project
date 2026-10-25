package com.cryptopilot.news.job;

import com.cryptopilot.news.config.NewsSummaryProperties;
import com.cryptopilot.news.model.SummaryRun;
import com.cryptopilot.news.service.NewsSummaryService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Schedules NSF-11 once the application is ready: one run, then the configured pause after it ends (every 5 minutes,
 * only articles without a summary), so two runs never overlap. Off unless {@code cryptopilot.news.summary.enabled},
 * which only the dev and prod profiles set.
 *
 * <p>Rule: NSF-11.
 */
@Component
public class NewsSummaryJob {

    private static final Logger log = LoggerFactory.getLogger(NewsSummaryJob.class);

    private final NewsSummaryService summaries;
    private final TaskScheduler scheduler;
    private final NewsSummaryProperties properties;
    private final Clock clock;

    public NewsSummaryJob(
            NewsSummaryService summaries, TaskScheduler scheduler, NewsSummaryProperties properties, Clock clock) {
        this.summaries = summaries;
        this.scheduler = scheduler;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("NSF-11 news summary is disabled");
            return;
        }
        scheduler.scheduleWithFixedDelay(
                this::run, clock.instant().plus(properties.initialDelay()), properties.period());
        log.info("NSF-11 news summary scheduled every {} with model {}", properties.period(), properties.model());
    }

    public void run() {
        try {
            SummaryRun run = summaries.summarizeDue();
            if (run.due() > 0) {
                log.info(
                        "NSF-11 summarized {} of {} articles ({} left for the next run)",
                        run.summarized(),
                        run.due(),
                        run.failed());
            }
        } catch (RuntimeException failure) {
            log.error("NSF-11 news summary failed; the next run retries", failure);
        }
    }
}
