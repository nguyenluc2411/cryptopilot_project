package com.cryptopilot.news.job;

import com.cryptopilot.news.config.NewsCrawlProperties;
import com.cryptopilot.news.model.CrawlRun;
import com.cryptopilot.news.service.CrawlService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Schedules NSF-10 once the application is ready: one run of the crawl, then the configured pause after it ends (every
 * 5 minutes, due sources only), so two runs never overlap. Off unless {@code cryptopilot.news.crawl.enabled}, which
 * only the dev and prod profiles set.
 *
 * <p>Rule: NSF-10.
 */
@Component
public class NewsCrawlJob {

    private static final Logger log = LoggerFactory.getLogger(NewsCrawlJob.class);

    private final CrawlService crawl;
    private final TaskScheduler scheduler;
    private final NewsCrawlProperties properties;
    private final Clock clock;

    public NewsCrawlJob(CrawlService crawl, TaskScheduler scheduler, NewsCrawlProperties properties, Clock clock) {
        this.crawl = crawl;
        this.scheduler = scheduler;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("NSF-10 news crawl is disabled");
            return;
        }
        scheduler.scheduleWithFixedDelay(
                this::run, clock.instant().plus(properties.initialDelay()), properties.period());
        log.info("NSF-10 news crawl scheduled every {} after the previous run", properties.period());
    }

    public void run() {
        try {
            CrawlRun run = crawl.crawlDue();
            if (run.sources() > 0) {
                log.info(
                        "NSF-10 read {} news sources ({} failed) and stored {} articles",
                        run.sources(),
                        run.failed(),
                        run.inserted());
            }
        } catch (RuntimeException failure) {
            log.error("NSF-10 news crawl failed; the next run retries", failure);
        }
    }
}
