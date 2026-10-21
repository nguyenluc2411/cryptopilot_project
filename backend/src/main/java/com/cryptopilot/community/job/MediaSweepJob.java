package com.cryptopilot.community.job;

import com.cryptopilot.community.config.MediaSweepProperties;
import com.cryptopilot.community.service.MediaSweepService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Runs the sweep of media files no post carries once a day. Orchestration only; the rule is in
 * {@link MediaSweepService}. One instance, no distributed lock: deleting a file already gone is not an error.
 *
 * <p>Rule: BR-44, BR-47; D-98.
 */
@Component
public class MediaSweepJob {

    private static final Logger log = LoggerFactory.getLogger(MediaSweepJob.class);

    private final MediaSweepService sweep;
    private final TaskScheduler scheduler;
    private final MediaSweepProperties properties;

    public MediaSweepJob(MediaSweepService sweep, TaskScheduler scheduler, MediaSweepProperties properties) {
        this.sweep = sweep;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Registers the daily run, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("Media sweep is disabled");
            return;
        }
        scheduler.schedule(this::run, new CronTrigger(properties.cron(), properties.zone()));
        log.info("Media sweep scheduled on '{}' ({})", properties.cron(), properties.zone());
    }

    /** One sweep; a failure is logged and left for the next day's run. */
    public void run() {
        try {
            int deleted = sweep.removeUnattached();
            log.info("Media sweep deleted {} files no post carries", deleted);
        } catch (RuntimeException failure) {
            log.error("Media sweep failed; the next run retries", failure);
        }
    }
}
