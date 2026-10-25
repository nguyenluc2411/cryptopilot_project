package com.cryptopilot.journal.job;

import com.cryptopilot.journal.config.FundingCorrectionProperties;
import com.cryptopilot.journal.service.FundingCorrectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Corrects, at a fixed interval, the funding of the closed trades that closed while a settlement was missing, so a
 * settlement stored late by NSF-04 reaches the journal. Orchestration only; the rule is in
 * {@link FundingCorrectionService}. One instance, no distributed lock: the update only changes a row still marked
 * incomplete, so two runs write the same answer.
 *
 * <p>Rule: BR-37; NSF-04; A-05; D-97 note of 2026-10-24.
 */
@Component
public class FundingCorrectionJob {

    private static final Logger log = LoggerFactory.getLogger(FundingCorrectionJob.class);

    private final FundingCorrectionService corrections;
    private final TaskScheduler scheduler;
    private final FundingCorrectionProperties properties;

    public FundingCorrectionJob(
            FundingCorrectionService corrections, TaskScheduler scheduler, FundingCorrectionProperties properties) {
        this.corrections = corrections;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** Registers the runs, when enabled. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("BR-37 journal funding correction is disabled");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::run, properties.interval());
        log.info("BR-37 journal funding correction scheduled every {}", properties.interval());
    }

    /** One run; a failure is logged and left for the next run. */
    public void run() {
        try {
            int corrected = corrections.correctIncompleteFunding(properties.batchSize());
            if (corrected > 0) {
                log.info("BR-37 corrected the funding of {} closed trades", corrected);
            }
        } catch (RuntimeException failure) {
            log.error("BR-37 journal funding correction failed; the next run retries", failure);
        }
    }
}
